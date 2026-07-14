(ns seon.dev.mcp
  "Serve one development MCP boundary for the CLJ writer and CLJS pod."
  (:require [babashka.classpath :as cp]
            [bencode.core :as b]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedOutputStream BufferedReader PushbackInputStream
            PushbackReader]
           [java.net InetSocketAddress Socket SocketTimeoutException]))

;; MCP server for the current Seon development runtimes.
;;
;; Bridges the Claude Code orchestrator to a running shadow-cljs build's
;; nREPL, pivoting clones into a CLJS runtime via shadow-cljs piggieback.
;;
;; Discovery: the shadow watcher writes its nREPL port to
;;   <project>/.shadow-cljs/nrepl.port
;; on startup. We read that on demand each call (cheap) so the server stays
;; correct across watcher restarts.
;;
;; Canonical tools:
;;   - eval_cljs          Eval CLJS code in the current pod runtime.
;;   - eval_clj           Eval CLJ code in the current writer runtime.
;; Compatibility tools retained under Claude's `seon_cljs` server name:
;;   - eval               Alias for eval_cljs.
;;   - create_session     Clone a persistent CLJS session for *1/*2/*3.
;;   - list_sessions      List known sessions.
;;   - stop_session       Forget a session (does not interrupt eval).
;;   - reload_deps        Trigger shadow's reload-deps! (CLJ side).
;;   - runtime_status     Report shadow watcher status + which runtimes connect.
;;
;; Protocol: JSON-RPC 2.0 over stdin/stdout.

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def project-root
  (or (System/getenv "SEON_PROJECT_ROOT")
      (System/getProperty "user.dir")))

;; The resolution GRAMMAR (parse-id / select-runtime) is the SAME code the
;; runtimes compile in and the CLJS suite tests — seon.dev.runtime-id is
;; CLJC precisely so this bb process can load it (registry C27: one
;; resolution rule, zero mirrored logic).
(cp/add-classpath (str project-root "/src"))
(require '[seon.dev.runtime-id :as rid])

(def own-cluster
  ;; The cluster THIS supervisor serves — basename of SEON_CLUSTER_DIR
  ;; (the same rule as seon.db.replica/database-name). The
  ;; singleton `default` session pins to the runtime advertising it.
  (rid/dir->cluster-name (or (System/getenv "SEON_CLUSTER_DIR")
                             "data/clusters/default")))

(def shadow-port-file (str project-root "/.shadow-cljs/nrepl.port"))

(def default-build-id ":client")
(def default-timeout-ms 30000)
(def connect-timeout-ms 5000)
(def default-output-tokens 4000)
(def max-output-tokens 16000)
(def min-output-tokens 64)
(def max-transport-events 256)

(def server-info {:name "seon" :version "0.2.0"})

;; sid (string) -> {:nrepl-session "<uuid>" :build "<:repl>" :created-at ms}
(def sessions (atom {}))

;; Multi-runtime addressing — the UNIFIED id scheme (mcp-agent-id-unification
;; PRD 2026-06-10; cluster-qualified per registry C27): agent_id IS the core
;; :seon.agent/id (e.g. "iCg-2606101519"), optionally cluster-qualified as
;; "<cluster>/<id>" (e.g. "default/root"). A runtime answers the probe with
;; its cluster plus agent ids projected from that cluster's database;
;; resolution is MEMBERSHIP across ALL active builds (no
;; hardwired build id) via seon.dev.runtime-id/select-runtime — a bare id
;; hosted by SEVERAL runtimes FAILS LOUD with the candidates (every cluster
;; hosts a "root"; never pick arbitrarily). client-id is internal shadow
;; plumbing and is NOT stable across a crash+restart, so we re-resolve
;; agent-id -> {build, client-id} per cache miss and re-pin when it changes.
;; This atom is a resolution CACHE keyed by the raw agent_id argument, not a
;; second id scheme.
;; agent-id (string) -> {:nrepl-session "<uuid>" :client-id <int> :build "<:client>" (:cluster "<name>")}
(def agent-sessions (atom {}))

;; [cluster session-id] -> one stateful io-prepl socket and its bound port.
(def clj-sessions (atom {}))
(def writer-port-file-override (System/getenv "SEON_WRITER_REPL_PORT_FILE"))

(def stdout-lock (Object.))
(def ^:dynamic *requested-output-tokens* default-output-tokens)

(defn- output-token-limit [requested]
  (min max-output-tokens
       (max min-output-tokens (or requested default-output-tokens))))

(defn- bounded-text [value requested]
  (let [limit (output-token-limit requested)
        clip-str (requiring-resolve 'seon.ai.tokens/clip-str)]
    (clip-str (str value) limit)))

(defn- transport-char-limit []
  ((requiring-resolve 'seon.ai.tokens/estimate-chars)
   (output-token-limit *requested-output-tokens*)))

(defn- cap-response-fields [response remaining]
  (reduce
   (fn [[result left truncated?] field]
     (if-let [value (get result field)]
       (let [value (str value)
             kept (subs value 0 (min left (count value)))
             cut? (< (count kept) (count value))]
         [(assoc result field kept) (- left (count kept)) (or truncated? cut?)])
       [result left truncated?]))
   [response remaining false]
   ["out" "err" "value" "ex"]))

(defn- content-text [value]
  (let [limit (output-token-limit *requested-output-tokens*)]
    (if (string? value)
      (bounded-text value limit)
      (let [encoded (json/generate-string value)
            clipped (bounded-text encoded limit)]
        (if (= encoded clipped)
          encoded
          (json/generate-string
           {:seon.dev.mcp/truncated? true
            :seon.dev.mcp/preview
            (bounded-text encoded (max 1 (- limit 32)))}))))))

;;; ---------------------------------------------------------------------------
;;; Logging (stderr only — stdout is reserved for JSON-RPC)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *debug* (= "1" (System/getenv "DEBUG")))

(defn- log-info [& args]
  (binding [*out* *err*]
    (apply println "[mcp INFO]" args)
    (flush)))

(defn- log-debug [& args]
  (when *debug*
    (binding [*out* *err*]
      (apply println "[mcp DEBUG]" args)
      (flush))))

(defn- log-error [& args]
  (binding [*out* *err*]
    (apply println "[mcp ERR ]" args)
    (flush)))

;;; ---------------------------------------------------------------------------
;;; Shadow port discovery
;;; ---------------------------------------------------------------------------

(defn- read-shadow-port
  "Returns the current shadow-cljs nREPL port, or nil if no watcher is running."
  []
  (try
    (let [f (java.io.File. shadow-port-file)]
      (when (.exists f)
        (some-> (slurp f) str/trim parse-long)))
    (catch Exception e
      (log-error "Failed to read shadow port:" (.getMessage e))
      nil)))

;;; ---------------------------------------------------------------------------
;;; nREPL bencode plumbing
;;; ---------------------------------------------------------------------------

(defn- ->str [x]
  (cond
    (bytes? x) (String. ^bytes x "UTF-8")
    (vector? x) (mapv ->str x)
    :else x))

(defn- normalize [m]
  (reduce-kv (fn [acc k v] (assoc acc (->str k) (->str v))) {} m))

(defn- connect [port]
  (let [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) connect-timeout-ms)
    s))

(defn- send-and-collect
  "Send a single message; collect responses until status contains 'done' or 'error'.
   Returns the vector of normalized responses."
  [^Socket sock msg timeout-ms]
  (let [in (PushbackInputStream. (.getInputStream sock))
        out (BufferedOutputStream. (.getOutputStream sock))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (b/write-bencode out msg)
    (.flush out)
    (loop [acc [] retained-chars 0 dropped 0]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if-not (pos? remaining)
          (conj acc {"status" ["error" "timeout"]})
          (do
            (.setSoTimeout sock (int remaining))
            (let [resp (try (b/read-bencode in)
                        (catch java.net.SocketTimeoutException _ ::timeout)
                        (catch java.io.EOFException _ ::eof))]
              (cond
                (= resp ::timeout)
                (conj acc {"status" ["error" "timeout"]})

                (= resp ::eof)
                (conj acc {"status" ["error" "eof"]})

                :else
                (let [normalized (normalize resp)
                      remaining-chars (max 0 (- (transport-char-limit)
                                                retained-chars))
                      [resp* _ truncated?]
                      (cap-response-fields normalized remaining-chars)
                      used (reduce + (map #(count (str (get resp* % "")))
                                          ["out" "err" "value" "ex"]))
                      terminal? (let [status (get resp* "status")]
                                  (and (vector? status)
                                       (or (some #{"done"} status)
                                           (some #{"error"} status))))
                      retain? (or terminal? (< (count acc) max-transport-events))
                      resp* (cond-> resp*
                              (or truncated? (not retain?))
                              (assoc "seon-mcp-truncated" true))
                      acc' (cond-> acc retain? (conj resp*))
                      dropped' (if retain? dropped (inc dropped))]
                  (if terminal?
                    (cond-> acc'
                      (pos? dropped')
                      (conj {"seon-mcp-dropped-events" dropped'}))
                    (recur acc' (+ retained-chars used) dropped')))))))))))

(defn- nrepl-clone-session
  "Returns a new bare nREPL session id string."
  [port]
  (try
    (with-open [sock (connect port)]
      (let [responses (send-and-collect sock {"op" "clone"} 5000)]
        (some #(get % "new-session") responses)))
    (catch Exception e
      (log-error "clone failed:" (.getMessage e))
      nil)))

(defn- nrepl-eval
  "Evaluate `code` in `nrepl-session`. Returns {:value :ns :out :err :ex :status}."
  [port nrepl-session code timeout-ms]
  (try
    (with-open [sock (connect port)]
      (let [msg {"op" "eval" "code" code "session" nrepl-session
                 "id" (str (random-uuid))}
            responses (send-and-collect sock msg timeout-ms)
            collect (fn [k] (->> responses (keep #(get % k)) (str/join "\n")))
            value (last (keep #(get % "value") responses))
            status (mapcat #(get % "status" []) responses)]
        {:value value
         :ns (last (keep #(get % "ns") responses))
         :out (collect "out")
         :err (collect "err")
         :ex (last (keep #(get % "ex") responses))
         :status (vec status)}))
    (catch Exception e
      (log-error "eval failed:" (.getMessage e))
      {:err (.getMessage e) :status ["error"]})))

(defn- nrepl-interrupt
  [port nrepl-session]
  (try
    (with-open [sock (connect port)]
      (send-and-collect sock {"op" "interrupt" "session" nrepl-session} 5000))
    (catch Exception e
      (log-error "interrupt failed:" (.getMessage e))
      nil)))

(defn- nrepl-close-session
  "Close a session on the nREPL server (frees server-side state).
   Best-effort — errors are logged and swallowed."
  [port nrepl-session]
  (try
    (with-open [sock (connect port)]
      (send-and-collect sock {"op" "close" "session" nrepl-session} 5000))
    (catch Exception e
      (log-error "close failed:" (.getMessage e))
      nil)))

(defn- nrepl-live-sessions
  "The set of session ids currently alive on the nREPL server, or nil
   when the server is unreachable (distinct from #{} = reachable, none)."
  [port]
  (try
    (with-open [sock (connect port)]
      (let [responses (send-and-collect sock {"op" "ls-sessions"} 5000)]
        (when-not (some #(some #{"error" "eof" "timeout"} (get % "status" [])) responses)
          (set (mapcat #(get % "sessions" []) responses)))))
    (catch Exception e
      (log-error "ls-sessions failed:" (.getMessage e))
      nil)))

;;; ---------------------------------------------------------------------------
;;; Runtime probing — cluster-qualified addressing (registry C27)
;;; ---------------------------------------------------------------------------

(defn- pin-session!
  "Clone a fresh nREPL session and pin it to a specific runtime (client-id)
   of the given build via `(shadow/nrepl-select build {:runtime-id cid})`.
   Returns the nREPL session id, or nil on failure. Evals on the returned
   session target ONLY that runtime (never the worker-global default)."
  [port build-id cid]
  (when-let [nrepl-sid (nrepl-clone-session port)]
    (nrepl-eval port nrepl-sid
                (str "(require '[shadow.cljs.devtools.api :as shadow]) "
                     "(shadow/nrepl-select " build-id " {:runtime-id " cid "})")
                10000)
    nrepl-sid))

(def probe-form
  ;; The canonical client derives agent ids from its database on every probe.
  ;; A runtime without this function is not an addressable Seon pod.
  "(seon.client/runtime-advertisement)")

(defn- probe-advertisements!
  "Probe EVERY runtime of every active build (no hardwired build id).
   Returns candidate maps: the runtime's advertisement
   (:seon.dev.runtime-id/cluster + :seon.dev.runtime-id/ids) merged with
   {:build \"<:client>\" :client-id <int>}. Runtimes that error on the
   probe (ns not loaded) are skipped. Probe sessions are closed after use
   (they historically leaked in the shadow JVM until a watcher restart)."
  [port]
  (let [clj-sid (nrepl-clone-session port)
        bs-res  (nrepl-eval port clj-sid
                            (str "(require '[shadow.cljs.devtools.api :as shadow]) "
                                 "(mapv (fn [b] [b (mapv :client-id (shadow/repl-runtimes b))]) "
                                 "(shadow/active-builds))")
                            10000)
        build->cids (try (read-string (or (:value bs-res) "[]"))
                         (catch Exception _ []))
        cands (vec (for [[build cids] build->cids
                         cid cids
                         :let [psid (pin-session! port (str build) cid)
                               v    (when psid
                                      (:value (nrepl-eval port psid probe-form 10000)))
                               _    (when psid (nrepl-close-session port psid))
                               adv  (try (when (and v (not (str/blank? v)))
                                           (read-string v))
                                         (catch Exception _ nil))
                               adv  (when (map? adv) adv)]
                         :when adv]
                     (merge adv {:build (str build) :client-id cid})))]
    (when clj-sid (nrepl-close-session port clj-sid))
    cands))

(defn- find-cluster-runtime!
  "client-id of the SINGLE runtime of `build-id` advertising `cluster`,
   or nil when none or several do (never pick arbitrarily)."
  [port build-id cluster]
  (let [cands (filterv #(and (= (:build %) build-id)
                             (= cluster (:seon.dev.runtime-id/cluster %)))
                       (probe-advertisements! port))]
    (when (= 1 (count cands))
      (:client-id (first cands)))))

;;; ---------------------------------------------------------------------------
;;; Session lifecycle (pivot a fresh nREPL session into CLJS)
;;; ---------------------------------------------------------------------------

(defn- gen-sid []
  (let [hex (-> (java.util.UUID/randomUUID) str (str/replace "-" ""))]
    (subs hex 0 6)))

(defn- create-cljs-session!
  "Clone a fresh nREPL session and pivot it into the given shadow build's
   CLJS REPL. Returns a sid (short id) the orchestrator passes back on
   subsequent evals. Stores {sid -> nrepl-session/build} in `sessions`.
   With `cid` the session is PINNED to that runtime (cluster-qualified
   addressing, C27); without it shadow's :runtime-select :latest picks."
  ([port build-id] (create-cljs-session! port build-id nil))
  ([port build-id cid]
  (let [nrepl-sid (nrepl-clone-session port)]
    (when nrepl-sid
      (let [pivot-resp (nrepl-eval port nrepl-sid
                                   (str "(require '[shadow.cljs.devtools.api :as shadow]) "
                                        (if cid
                                          (str "(shadow/nrepl-select " build-id " {:runtime-id " cid "})")
                                          (str "(shadow/nrepl-select " build-id ")")))
                                   10000)]
        (log-debug "pivot result:" pivot-resp)
        ;; Validate the pivot. shadow/nrepl-select returns [:selected <build>]
        ;; on success, :missing-nrepl-middleware otherwise, and throws on an
        ;; unknown build. Storing a session whose pivot failed produces the
        ;; wedged-default symptom: evals fall through to the CLJ compiler with
        ;; no *ns* bound (NPE from Compiler.currentNS()). Refuse to store it.
        (if (some-> (:value pivot-resp) (str/includes? ":selected"))
          (let [sid (gen-sid)]
            (swap! sessions assoc sid
                   (cond-> {:nrepl-session nrepl-sid
                            :build build-id
                            :created-at (System/currentTimeMillis)
                            :pivot-value (:value pivot-resp)}
                     cid (assoc :client-id cid)))
            sid)
          (do (log-error "pivot into" build-id "failed:" (pr-str (dissoc pivot-resp :out)))
              (nrepl-close-session port nrepl-sid)
              nil)))))))

(defn- require-port! []
  (or (read-shadow-port)
      (throw (ex-info "no shadow-cljs watcher running (no .shadow-cljs/nrepl.port)"
                      {:port-file shadow-port-file}))))

(defn- get-or-create-session!
  "If sid is provided, look it up. If sid is 'default', use a singleton
   default-build session, creating it lazily when absent. Throws when the
   default session can't be created (pivot failed) or sid is unknown."
  [sid-arg]
  (let [port (require-port!)
        sid (or sid-arg "default")]
    (or (when-let [s (get @sessions sid)]
          {:sid sid :session-info s})
        (if (= sid "default")
          ;; C27: the default session pins to the runtime advertising THIS
          ;; supervisor's own cluster — :runtime-select :latest used to pin
          ;; whichever pod connected last (a watched bench pod, not the
          ;; default pod). A missing or duplicate runtime fails loudly; an
          ;; unpinned :latest fallback could silently select another cluster.
          (let [cands (try (filterv #(= (:build %) default-build-id)
                                    (probe-advertisements! port))
                           (catch Exception e
                             (log-error "advertisement probe failed:" (.getMessage e))
                             []))
                own (filterv #(= own-cluster
                                  (:seon.dev.runtime-id/cluster %))
                             cands)]
            (when-not (= 1 (count own))
              (throw
               (ex-info
                (str "expected one " default-build-id " runtime for cluster '"
                     own-cluster "', found " (count own)
                     ". Live advertisements: "
                     (pr-str
                      (mapv #(select-keys
                              % [:client-id
                                 :seon.dev.runtime-id/cluster
                                 :seon.dev.runtime-id/ids])
                            cands))
                     ". Is the pod up exactly once? bin/seon status")
                {:cluster own-cluster :matches (count own)})))
            (if-let [new-sid
                     (create-cljs-session! port default-build-id
                                           (:client-id (first own)))]
              (let [info (get @sessions new-sid)]
                (swap! sessions
                       (fn [m]
                         (-> m (dissoc new-sid) (assoc "default" info))))
                {:sid "default" :session-info info})
              (throw
               (ex-info
                (str "failed to create default CLJS session — pivot into "
                     default-build-id " failed (watcher up? build watched?)")
                {:build default-build-id}))))
          (throw (ex-info (str "unknown session id: " sid
                               " (it may have been swept after a watcher restart — "
                               "use create_session for a fresh one)")
                          {:sid sid}))))))

(defn- sweep-dead-sessions!
  "Liveness sweep: reap tracked sessions whose nREPL session no longer
   exists on the shadow server. Cloned nREPL sessions die when the watcher
   restarts but were historically never reaped here (26+ stale entries
   observed). Run on list_sessions/create_session. When the watcher is
   unreachable every tracked session is dead by definition — reap all.
   Sweeps the agent-session resolution cache by the same criterion.
   Returns {:swept [..sids..] :agent-swept [..agent-ids..]}."
  []
  (let [port (read-shadow-port)
        live (when port (nrepl-live-sessions port))
        ;; No watcher → every cloned session is gone → reap all. Watcher up
        ;; but ls-sessions errored (nil despite a port) → can't tell → keep
        ;; everything rather than reap live sessions on a transient failure.
        dead? (cond
                (nil? port) (constantly true)
                (nil? live) (constantly false)
                :else (fn [info] (not (contains? live (:nrepl-session info)))))
        dead (vec (keep (fn [[sid info]] (when (dead? info) sid)) @sessions))
        agent-dead (vec (keep (fn [[aid info]] (when (dead? info) aid)) @agent-sessions))]
    (when (seq dead)
      (swap! sessions #(apply dissoc % dead))
      (log-info "swept dead sessions:" (str/join ", " dead)))
    (when (seq agent-dead)
      (swap! agent-sessions #(apply dissoc % agent-dead))
      (log-info "swept dead agent sessions:" (str/join ", " agent-dead)))
    {:swept dead :agent-swept agent-dead}))

;;; ---------------------------------------------------------------------------
;;; Multi-runtime: resolve agent-id -> current client-id, pin a session to it
;;; ---------------------------------------------------------------------------

(defn- resolve-agent-runtime!
  "Probe-resolve `agent-id` — bare (`root`) or
   cluster-qualified (\"default/root\") — against EVERY active build's
   runtimes via their advertisements, applying THE decision rule
   (seon.dev.runtime-id/select-runtime): returns its envelope —
   ::resolution :match (+ ::runtime, the advertisement merged with
   {:build :client-id}), :ambiguous (+ ::runtimes, all matches), or
   :none. A bare id present in SEVERAL runtime databases (every cluster has a
   \"root\") is ambiguous and FAILS LOUD upstream — never an arbitrary
   pick (registry C27). Survives restart for free: a respawned process
   advertises the same ids under its new client-id."
  [port agent-id]
  (let [parsed (rid/parse-id agent-id)
        cands  (probe-advertisements! port)]
    (rid/select-runtime (assoc parsed :seon.dev.runtime-id/candidates cands))))

(defn- ensure-agent-session!
  "Return {:nrepl-session sid :client-id cid :build b (:cluster c)} pinned
   to agent-id's CURRENT runtime, {:ambiguous [cands]} when several live
   runtimes host it, or nil when none does. Reuses the cached pinned
   session if the agent's build+client-id are unchanged; otherwise
   re-resolves and re-pins (the no-restart survival path after a
   crash+respawn)."
  [port agent-id]
  (let [res (resolve-agent-runtime! port agent-id)]
    (case (:seon.dev.runtime-id/resolution res)
      :none
      (do
        (when-let [cached (get @agent-sessions agent-id)]
          (nrepl-close-session port (:nrepl-session cached))
          (swap! agent-sessions dissoc agent-id))
        nil)
      :ambiguous
      (do
        (when-let [cached (get @agent-sessions agent-id)]
          (nrepl-close-session port (:nrepl-session cached))
          (swap! agent-sessions dissoc agent-id))
        {:ambiguous (:seon.dev.runtime-id/runtimes res)})
      :match
      (let [{:keys [build client-id] :as m} (:seon.dev.runtime-id/runtime res)
            cluster (:seon.dev.runtime-id/cluster m)
            cached  (get @agent-sessions agent-id)]
        (if (and cached
                 (= client-id (:client-id cached))
                 (= build (:build cached)))
          cached
          (do
            (when cached
              (nrepl-close-session port (:nrepl-session cached))
              (swap! agent-sessions dissoc agent-id))
            (when-let [nrepl-sid (pin-session! port build client-id)]
            (let [info (cond-> {:nrepl-session nrepl-sid :client-id client-id :build build}
                         cluster (assoc :cluster cluster))]
              (swap! agent-sessions assoc agent-id info)
              info))))))))

;;; ---------------------------------------------------------------------------
;;; MCP result builders
;;; ---------------------------------------------------------------------------

(defn- mcp-success [value]
  {:content [{:type "text"
              :text (content-text value)}]})

(defn- mcp-error [error]
  (let [data (if (map? error)
               error
               {:seon.dev.mcp/error (str error)})]
    {:content [{:type "text"
                :text (content-text data)}]
     :isError true}))

(defn- render-eval-result [{:keys [value ns out err ex status]} sid]
  (if (or (some #{"error"} status)
          (#{":repl/exception!" ":repl/print-error!"} value))
    (mcp-error {:seon.dev.mcp/failure :evaluation
                :seon.dev.mcp/runtime "cljs-pod"
                :seon.dev.mcp/session-id sid
                :seon.dev.mcp/value value
                :seon.dev.mcp/ns ns
                :seon.dev.mcp/out out
                :seon.dev.mcp/err err
                :seon.dev.mcp/exception ex
                :seon.dev.mcp/status status})
    (let [parts (cond-> []
                (seq out) (conj (str "stdout:\n" out))
                (seq err) (conj (str "stderr:\n" err))
                value (conj (str "=> " value))
                ns (conj (str "ns: " ns))
                ex (conj (str "ex: " ex)))
          header (str "session=" sid)]
      (mcp-success (str header "\n" (str/join "\n" parts))))))

;;; ---------------------------------------------------------------------------
;;; Writer io-prepl transport
;;; ---------------------------------------------------------------------------

(defn- valid-cluster? [cluster]
  (boolean (re-matches #"[A-Za-z0-9._-]+" cluster)))

(defn- writer-port-file
  "Return the selected cluster's writer io-prepl port file."
  [cluster]
  (when-not (valid-cluster? cluster)
    (throw (ex-info "Invalid cluster name." {:seon.dev.mcp/cluster cluster})))
  (let [configured (when (= cluster own-cluster) writer-port-file-override)
        file (io/file (or configured
                          (str "tmp/seon-writer-repl-port-" cluster)))]
    (if (.isAbsolute file) file (io/file project-root (.getPath file)))))

(defn- read-writer-port
  "Read the current writer io-prepl port for a cluster."
  [cluster]
  (let [file (writer-port-file cluster)]
    (when-not (.isFile file)
      (throw (ex-info "Writer port file is unavailable."
                      {:seon.dev.mcp/cluster cluster
                       :seon.dev.mcp/port-file (.getPath file)})))
    (let [port (some-> (slurp file) str/trim parse-long)]
      (when-not (and port (<= 1 port 65535))
        (throw (ex-info "Writer port file is malformed."
                        {:seon.dev.mcp/cluster cluster
                         :seon.dev.mcp/port-file (.getPath file)})))
      port)))

(defn- close-clj-session! [key]
  (when-let [{:keys [socket]} (get @clj-sessions key)]
    (swap! clj-sessions dissoc key)
    (try (.close ^Socket socket) (catch Throwable _))))

(defn- open-clj-session! [cluster session-id port]
  (let [socket (Socket.)]
    (.connect socket (InetSocketAddress. "127.0.0.1" (int port))
              connect-timeout-ms)
    (let [session {:socket socket
                   :reader (PushbackReader. (io/reader socket))
                   :writer (io/writer socket)
                   :port port
                   :cluster cluster
                   :session-id session-id}]
      (swap! clj-sessions assoc [cluster session-id] session)
      session)))

(defn- current-clj-session! [cluster session-id]
  (let [key [cluster session-id]
        port (read-writer-port cluster)
        cached (get @clj-sessions key)]
    (cond
      (and cached (= port (:port cached))) cached

      cached
      (do
        (close-clj-session! key)
        (if (= "default" session-id)
          (open-clj-session! cluster session-id port)
          (throw (ex-info "Named CLJ session ended with the writer restart."
                          {:seon.dev.mcp/cluster cluster
                           :seon.dev.mcp/session-id session-id
                           :seon.dev.mcp/retry-with-new-session true}))))

      :else
      (open-clj-session! cluster session-id port))))

(defn- require-single-clj-form! [code]
  ;; io-prepl emits exactly one :ret PER FORM, not per socket write. Sending
  ;; several forms and returning after the first would leave queued events that
  ;; shift every later call on this stateful session. Enforce the public tool's
  ;; one-form contract before writing anything; callers use `(do ...)` when
  ;; they intentionally need several expressions in one evaluation.
  (try
    (let [eof (Object.)
          reader (PushbackReader. (java.io.StringReader. code))]
      (binding [*read-eval* false]
        (let [first-form (read {:eof eof :read-cond :allow} reader)
              second-form (read {:eof eof :read-cond :allow} reader)]
          (when (identical? eof first-form)
            (throw (ex-info "CLJ evaluation requires one form."
                            {:seon.dev.mcp/failure :empty-form})))
          (when-not (identical? eof second-form)
            (throw (ex-info "CLJ evaluation accepts exactly one form; wrap intentional sequences in `(do ...)`."
                            {:seon.dev.mcp/failure :multiple-forms})))
          code)))
    (catch clojure.lang.ExceptionInfo exception
      (if (:seon.dev.mcp/failure (ex-data exception))
        (throw exception)
        (throw (ex-info "CLJ evaluation form is unreadable."
                        {:seon.dev.mcp/failure :invalid-form}
                        exception))))
    (catch Throwable throwable
      (throw (ex-info "CLJ evaluation form is unreadable."
                      {:seon.dev.mcp/failure :invalid-form}
                      throwable)))))

(defn- collect-prepl-response! [{:keys [socket reader]} timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
   (loop [events [] dropped 0 retained-chars 0]
    (let [remaining (- deadline (System/currentTimeMillis))
          _ (when-not (pos? remaining)
              (throw (SocketTimeoutException. "io-prepl deadline exceeded")))
          _ (.setSoTimeout ^Socket socket (int remaining))
          event (edn/read {:eof ::eof} reader)]
      (cond
        (= ::eof event)
        (throw (ex-info "Writer closed the io-prepl session."
                        {:seon.dev.mcp/failure :transport-closed}))

        (not (map? event))
        (throw (ex-info "Writer emitted malformed io-prepl data."
                        {:seon.dev.mcp/failure :malformed-prepl
                         :seon.dev.mcp/value (pr-str event)}))

        (= :ret (:tag event))
        (let [event (if (string? (:val event))
                      (update event :val bounded-text *requested-output-tokens*)
                      event)]
          (cond-> events
            (pos? dropped) (conj {:tag :seon.dev.mcp/dropped-events :count dropped})
            true (conj event)))

        :else
        (let [available (max 0 (- (transport-char-limit) retained-chars))
              raw (when (string? (:val event)) (:val event))
              kept (when raw (subs raw 0 (min available (count raw))))
              truncated? (and raw (< (count kept) (count raw)))
              event (cond-> event
                      raw (assoc :val kept)
                      truncated? (assoc :seon.dev.mcp/truncated? true))
              used (count (or kept ""))]
          (if (< (count events) (dec max-transport-events))
            (recur (conj events event) dropped (+ retained-chars used))
            (recur events (inc dropped) retained-chars))))))))

(defn- execute-clj-eval
  "Evaluate one form in a stateful writer io-prepl session."
  [{:keys [code cluster session_id timeout_ms]}]
  (let [cluster (or cluster own-cluster)
        session-id (or session_id "default")
        timeout-ms (min 120000 (max 1 (or timeout_ms default-timeout-ms)))
        key [cluster session-id]
        validation (try {:code (require-single-clj-form! code)}
                        (catch Throwable throwable {:error throwable}))]
    (if-let [throwable (:error validation)]
      (mcp-error (merge {:seon.dev.mcp/runtime "clj-writer"
                         :seon.dev.mcp/cluster cluster
                         :seon.dev.mcp/session-id session-id
                         :seon.dev.mcp/error (ex-message throwable)}
                        (ex-data throwable)))
      (try
      (let [code (:code validation)
            {:keys [writer] :as session}
            (current-clj-session! cluster session-id)]
        (.write ^java.io.Writer writer (str code "\n"))
        (.flush ^java.io.Writer writer)
        (let [events (collect-prepl-response! session timeout-ms)
              ret (last events)
              response {:seon.dev.mcp/runtime "clj-writer"
                        :seon.dev.mcp/cluster cluster
                        :seon.dev.mcp/session-id session-id
                        :seon.dev.mcp/events events}]
          (if (:exception ret)
            (mcp-error (assoc response :seon.dev.mcp/failure :evaluation))
            (mcp-success response))))
      (catch SocketTimeoutException _
        (close-clj-session! key)
        (mcp-error {:seon.dev.mcp/failure :timeout
                    :seon.dev.mcp/runtime "clj-writer"
                    :seon.dev.mcp/cluster cluster
                    :seon.dev.mcp/session-id session-id
                    :seon.dev.mcp/timeout-ms timeout-ms}))
      (catch Throwable throwable
        (close-clj-session! key)
        (mcp-error (merge {:seon.dev.mcp/failure :transport
                           :seon.dev.mcp/runtime "clj-writer"
                           :seon.dev.mcp/cluster cluster
                           :seon.dev.mcp/session-id session-id
                           :seon.dev.mcp/error (ex-message throwable)}
                          (ex-data throwable))))))))

;;; ---------------------------------------------------------------------------
;;; Tool implementations
;;; ---------------------------------------------------------------------------

(defn- stale-runtime?
  "True if the eval result is the kind of failure a stale or missing
   JS runtime produces. Shadow surfaces these in TWO distinct shapes:

   1. **Loud failure** — :err carries 'No available JS runtime' or
      similar message. Detected by substring.
   2. **Silent failure** — :status contains \"error\" but :err is
      empty (or just whitespace), :value is nil. This happens when
      shadow's nREPL session is bound to a :runtime-id of a runtime
      that has disconnected but hasn't been GC'd yet. shadow returns
      a status-only error envelope; the message is lost.

   Both signal the same recovery: drop the session, create a fresh
   one (which under :repl {:runtime-select :latest} routes to the
   currently-connected runtime), retry.

   See research/shadow-node-runtime-2026-05-23.md §Q3 + gotcha 3."
  [{:keys [err value status]}]
  (or
    ;; Shape 1 — :err message match
    (and (seq err)
         (or (str/includes? err "No available JS runtime")
             (str/includes? err "previously used runtime disappeared")
             (str/includes? err "client-not-found")))
    ;; Shape 2 — status=error with no value AND no useful :err
    (and (some #{"error"} status)
         (nil? value)
         (or (nil? err) (str/blank? err)))
    ;; Shape 3 — WEDGED session (the historical `default` NPE): the session
    ;; fell out of the CLJS REPL (worker restart under it / half-failed
    ;; pivot) so evals hit the CLJ compiler with no *ns* bound:
    ;;   NullPointerException ... Compiler.currentNS() is null
    ;; This state lives in the shadow JVM's session, so it survives pod
    ;; restarts; recovery is the same drop-session-and-recreate.
    (and (seq err)
         (str/includes? err "NullPointerException")
         (or (str/includes? err "currentNS")
             (str/includes? err "Compiler")))))

(defn- retry-with-fresh-session!
  "Drop the cached session, create a new one, and re-attempt eval.
   Used as the recovery path when an eval lands on a dead runtime.
   With shadow's `:repl {:runtime-select :latest}` set (see shadow-
   cljs.edn), the fresh session will route to whatever runtime is
   currently connected."
  [port session_id code timeout]
  (let [sid (or session_id "default")]
    ;; Close the dead/wedged session server-side too — dropping only the
    ;; local entry leaks the cloned session in the shadow JVM.
    (when-let [old (get @sessions sid)]
      (nrepl-close-session port (:nrepl-session old)))
    (swap! sessions dissoc sid))
  (let [{:keys [sid session-info]} (get-or-create-session! session_id)
        nrepl-sid (:nrepl-session session-info)
        result (nrepl-eval port nrepl-sid code timeout)]
    {:sid sid :result result}))

(defn- diagnose-no-runtime
  "Build the user-facing error when both initial eval AND retry fail
   with no runtime. Surfaces concrete next steps so the agent can
   act without consulting a separate runtime_status call."
  []
  (str "No CLJS runtime connected to shadow-cljs watcher.\n"
       "Likely causes:\n"
       "  - The pod isn't running. Start with: bin/seon up\n"
       "  - The pod was compiled with `clj -M:cljs compile` "
       "(no REPL client). Restart cljs-watch + pod: "
       "bin/seon restart\n"
       "  - Pod just started and websocket is still connecting "
       "(wait 1-2s, retry)."))

(defn- ambiguous-runtime-message
  "The FAIL-LOUD text when a bare agent_id matches several live runtimes:
   every candidate as its cluster-qualified handle, so the caller can
   re-address deterministically (registry C27)."
  [agent-id cands]
  (let [bare (:seon.dev.runtime-id/id (rid/parse-id agent-id))
        line (fn [{:keys [build client-id] :as c}]
               (str "  " (or (:seon.dev.runtime-id/cluster c) "?") "/" bare
                    "  (build " build ", client-id " client-id ")"))]
    (str "agent_id '" agent-id "' is AMBIGUOUS — " (count cands)
         " live runtimes host it:\n"
         (str/join "\n" (map line cands))
         "\nRefusing to pick one arbitrarily. Qualify the id with its cluster"
         " — e.g. '" (some :seon.dev.runtime-id/cluster cands)
         "/" bare "'.")))

(defn- execute-agent-eval
  "Eval `code` in the runtime owning `agent-id` — bare or cluster-qualified
   ('default/root'); client-id is resolved + pinned under the hood. A bare
   id hosted by several runtimes fails loud with the candidate list. On a
   stale runtime (agent crashed/respawned with a new client-id), drop the
   cached pinned session, re-resolve, re-pin, and retry — with a short
   reconnect window so a just-respawned agent's websocket has time to
   re-register. No MCP-server or shadow restart is involved."
  [port agent-id code timeout]
  (let [bare (:seon.dev.runtime-id/id (rid/parse-id agent-id))]
    (loop [tries 0]
      (let [sess (ensure-agent-session! port agent-id)]
        (cond
          (:ambiguous sess)
          (mcp-error (ambiguous-runtime-message agent-id (:ambiguous sess)))

          sess
          (let [{:keys [nrepl-session client-id cluster]} sess
                result (nrepl-eval port nrepl-session code timeout)]
            (if (and (stale-runtime? result) (< tries 10))
              (do (nrepl-close-session port nrepl-session)
                  (swap! agent-sessions dissoc agent-id)
                  (Thread/sleep 200)
                  (recur (inc tries)))
              (render-eval-result
                result
                (str "agent:" (when cluster (str cluster "/")) bare "#" client-id))))

          ;; No live runtime for this agent yet — it may be (re)connecting.
          (< tries 10)
          (do (Thread/sleep 200) (recur (inc tries)))

          :else
          (mcp-error (str "No live runtime database contains agent_id '" agent-id "'. "
                          "agent_id is the core :seon.agent/id, optionally "
                          "cluster-qualified as '<cluster>/<id>' (e.g. "
                          "'default/root' — a qualified id only matches a "
                          "runtime advertising that cluster). "
                          "Check (seon.client/runtime-advertisement) on the "
                          ":client runtime, or bin/seon status / restart.")))))))

(defn- execute-eval [{:keys [code session_id timeout_ms agent_id cluster]}]
  (when (and cluster (not= cluster own-cluster) (str/blank? (or agent_id "")))
    (throw (ex-info "A non-default CLJS cluster requires agent_id '<cluster>/<id>'."
                    {:seon.dev.mcp/cluster cluster
                     :seon.dev.mcp/own-cluster own-cluster})))
  (let [port (require-port!)
        timeout (min 120000 (max 1 (or timeout_ms default-timeout-ms)))]
   (if (and agent_id (not (str/blank? agent_id)))
    (execute-agent-eval port agent_id code timeout)
    (let [{:keys [sid session-info]} (get-or-create-session! session_id)
        nrepl-sid (:nrepl-session session-info)
        result (nrepl-eval port nrepl-sid code timeout)]
    (if (stale-runtime? result)
      ;; Self-heal: try with a fresh session. With :runtime-select :latest
      ;; the new session routes to whatever runtime is currently up. If
      ;; the pod is mid-restart, give it a brief window to reconnect.
      (loop [tries 0]
        (let [{retry-sid :sid retry-result :result}
              (retry-with-fresh-session! port session_id code timeout)]
          (cond
            (not (stale-runtime? retry-result))
            (render-eval-result retry-result retry-sid)

            ;; Pod might be reconnecting — back off briefly and retry.
            (< tries 10)
            (do (Thread/sleep 200) (recur (inc tries)))

            :else
            (mcp-error (diagnose-no-runtime)))))
      (render-eval-result result sid))))))

(defn- execute-create-session [{:keys [build cluster]}]
  (sweep-dead-sessions!)
  (let [port (require-port!)
        build-id (or build default-build-id)]
    (if (and cluster (not (str/blank? cluster)))
      ;; C27: cluster-pinned session — refuse to guess when the cluster's
      ;; runtime isn't uniquely identifiable on this build.
      (if-let [cid (find-cluster-runtime! port build-id cluster)]
        (if-let [sid (create-cljs-session! port build-id cid)]
          (mcp-success (str "session=" sid " build=" build-id
                            " cluster=" cluster " client-id=" cid
                            " (use this sid in mcp__seon_cljs__eval's session_id)"))
          (mcp-error (str "failed to clone/pivot CLJS session into " build-id
                          " pinned to cluster " cluster
                          " (watcher up? build watched? see stderr log)")))
        (mcp-error (str "no SINGLE runtime of build " build-id
                        " advertises cluster '" cluster "'. Live advertisements: "
                        (pr-str (mapv #(select-keys % [:build :client-id
                                                       :seon.dev.runtime-id/cluster
                                                       :seon.dev.runtime-id/ids])
                                      (probe-advertisements! port))))))
      (if-let [sid (create-cljs-session! port build-id)]
        (mcp-success (str "session=" sid " build=" build-id
                          " (use this sid in mcp__seon_cljs__eval's session_id)"))
        (mcp-error (str "failed to clone/pivot CLJS session into " build-id
                        " (watcher up? build watched? see stderr log)"))))))

(defn- execute-list-sessions [_args]
  (let [{:keys [swept agent-swept]} (sweep-dead-sessions!)
        now (System/currentTimeMillis)
        rows (for [[sid info] @sessions]
               (format "  %s  build=%s  nrepl-sid=%s  age=%.1fs"
                       sid (:build info) (:nrepl-session info)
                       (/ (double (- now (:created-at info))) 1000.0)))
        port (read-shadow-port)]
    (mcp-success (str "shadow port: " (or port "<no watcher>")
                      "\nsessions:\n" (if (seq rows) (str/join "\n" rows) "  (none)")
                      (when (seq swept)
                        (str "\nswept (dead nREPL session): " (str/join ", " swept)))
                      (when (seq agent-swept)
                        (str "\nswept agent cache: " (str/join ", " agent-swept)))))))

(defn- execute-stop-session [{:keys [session_id]}]
  (if-let [info (get @sessions session_id)]
    (do (swap! sessions dissoc session_id)
        (when-let [port (read-shadow-port)]
          (nrepl-close-session port (:nrepl-session info)))
        (mcp-success (str "stopped session " session_id)))
    (mcp-error (str "no such session: " session_id))))

(defn- execute-reload-deps [{:keys [session_id]}]
  (let [port (require-port!)
        ;; Reload-deps runs on the CLJ side (compiler), so we use a bare
        ;; nREPL session (no CLJS pivot) for the JVM eval, then trigger
        ;; the watcher reload of the build the orchestrator is on.
        clj-sid (nrepl-clone-session port)
        build (or (get-in @sessions [(or session_id "default") :build])
                  default-build-id)
        code (str "(do (require '[shadow.cljs.devtools.api :as shadow]) "
                  "(shadow.cljs.devtools.api/reload-deps! " build "))")
        result (try (nrepl-eval port clj-sid code 60000)
                    (finally
                      (when clj-sid (nrepl-close-session port clj-sid))))]
    (render-eval-result result (or session_id "default"))))

(defn- execute-runtime-status [_args]
  (let [port (read-shadow-port)
        info (when port
               (let [sid (nrepl-clone-session port)
                     code "(do (require '[shadow.cljs.devtools.api :as shadow]) (pr-str (mapv (fn [b] {:build b :runtimes (count (shadow/repl-runtimes b))}) (shadow/active-builds))))"
                     r (try (nrepl-eval port sid code 10000)
                            (finally
                              (when sid (nrepl-close-session port sid))))]
                 (:value r)))
        ;; Per-runtime database projections: the cluster + agent ids each
        ;; connected pod exposes to agent_id resolution.
        adverts (when port
                  (try (probe-advertisements! port)
                       (catch Exception e
                         (log-error "advertisement probe failed:" (.getMessage e))
                         nil)))
        advert-lines (map (fn [{:keys [build client-id] :as c}]
                            (str "  " build "#" client-id
                                 "  cluster=" (or (:seon.dev.runtime-id/cluster c) "?")
                                 "  ids=" (pr-str (:seon.dev.runtime-id/ids c))))
                          adverts)]
    (mcp-success (str "shadow nREPL port: " (or port "<no watcher>")
                      "\nbuilds: " (or info "<unable to query>")
                      "\nruntime database advertisements (cluster + agent ids):\n"
                      (if (seq advert-lines) (str/join "\n" advert-lines) "  (none)")
                      "\nmcp sessions: " (count @sessions)
                      "\nown cluster (default session pins to it): " own-cluster))))

;;; ---------------------------------------------------------------------------
;;; Tool registry
;;; ---------------------------------------------------------------------------

(def tools
  [{:name "eval_cljs"
    :description "Evaluate ClojureScript in the current pod through Shadow nREPL. The default session is stateful and pinned to this checkout's cluster; agent_id preserves cluster-qualified pod routing."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}
                               :cluster {:type "string"
                                         :description "Cluster served by this checkout (default: default). For another live cluster, provide cluster-qualified agent_id."}
                               :agent_id {:type "string"
                                          :description "Database agent id, optionally '<cluster>/<id>' for deterministic routing."}
                               :session_id {:type "string"
                                            :description "Stateful CLJS session id; default is self-healing after pod/watcher restart."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}
                               :max_output_tokens {:type "integer" :minimum 64 :maximum 16000}}
                  :required ["code"]}}

   {:name "eval_clj"
    :description "Evaluate one Clojure form in the selected writer's stateful loopback io-prepl session. The default session reconnects after writer restart; named sessions report that their state was lost."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}
                               :cluster {:type "string" :description "Operator cluster name. Defaults to this MCP server's cluster."}
                               :session_id {:type "string" :description "Stateful io-prepl session id. Defaults to 'default'."}
                               :timeout_ms {:type "integer" :minimum 1 :maximum 120000}
                               :max_output_tokens {:type "integer" :minimum 64 :maximum 16000}}
                  :required ["code"]}}

   ;; Claude compatibility alias retained while existing prompts migrate.
   {:name "eval"
    :description "Evaluate ClojureScript code in a watched shadow-cljs build's runtime. Returns {value, ns, out, err}. Reload-on-save is automatic — your source edits propagate without restart. Use session_id 'default' for the singleton :repl-build session, or a 6-char hex sid from create_session for an isolated session."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"
                                      :description "ClojureScript code to evaluate. Must reference fully-qualified names (cljs.user has no implicit aliases beyond core)."}
                               :agent_id {:type "string"
                                          :description "Address the pod for a database agent: the core :seon.agent/id, optionally cluster-qualified as '<cluster>/<id>' (for example 'default/root'). Each pod projects its agent ids directly from its database. A bare id present in several clusters errors with the candidates; it is never assigned arbitrarily. Process restart is handled by re-resolving the new Shadow client id. Omit this field to use the default session pinned to this checkout's cluster."}
                               :session_id {:type "string"
                                            :description "Session sid. 'default' for the singleton :repl build session (auto-created). Or a sid from create_session. Ignored when agent_id is set."}
                               :timeout_ms {:type "integer"
                                            :description "Eval timeout. Default 30000ms."}}
                  :required ["code"]}}

   {:name "create_session"
    :description "Clone a fresh nREPL session and pivot it into the named shadow-cljs build's CLJS REPL. Returns a 6-char sid. Use this when you want a session distinct from 'default'. Pass cluster to PIN the session to a specific cluster's runtime — without it, shadow's :runtime-select :latest picks whichever runtime of the build connected last (with several pods on one build that may be another cluster's pod)."
    :inputSchema {:type "object"
                  :properties {:build {:type "string"
                                       :description "Shadow build id keyword string, e.g. ':client'. Defaults to ':client'."}
                               :cluster {:type "string"
                                         :description "Cluster name (e.g. 'default'). Pins the session to the single runtime of the build advertising this cluster; errors (listing live advertisements) when none or several do."}}
                  :required []}}

   {:name "list_sessions"
    :description "List active CLJS REPL sessions tracked by this MCP server, plus the shadow nREPL port. Runs a liveness sweep first: sessions whose nREPL session died (e.g. watcher restart) are reaped and reported."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name "stop_session"
    :description "Forget a session and close it on the nREPL server (does not interrupt running eval)."
    :inputSchema {:type "object"
                  :properties {:session_id {:type "string"}}
                  :required ["session_id"]}}

   {:name "reload_deps"
    :description "Trigger shadow-cljs's reload-deps! to pick up new clojars/npm deps in the watched build without restarting the watcher."
    :inputSchema {:type "object"
                  :properties {:session_id {:type "string" :description "Which session's build to reload-deps on. Defaults to 'default'."}}
                  :required []}}

   {:name "runtime_status"
    :description "Report shadow watcher state — port, active builds, runtime counts, mcp session count."
    :inputSchema {:type "object" :properties {} :required []}}])

;;; ---------------------------------------------------------------------------
;;; JSON-RPC plumbing
;;; ---------------------------------------------------------------------------

(defn- send-response [resp]
  (let [s (json/generate-string resp)]
    (log-debug "<<" s)
    (locking stdout-lock (println s) (flush))))

(defn- send-result [id result]
  (send-response {:jsonrpc "2.0" :id id :result result}))

(defn- send-error [id code message]
  (send-response {:jsonrpc "2.0" :id id
                  :error {:code code :message message}}))

(defn- execute-tool [name args]
  (let [args' (or args {})
        args' (reduce-kv (fn [m k v] (assoc m (keyword k) v)) {} args')]
    (binding [*requested-output-tokens*
              (output-token-limit (:max_output_tokens args'))]
      (case name
        "eval_cljs" (execute-eval args')
        "eval_clj" (execute-clj-eval args')
        "eval" (execute-eval args')
        "create_session" (execute-create-session args')
        "list_sessions" (execute-list-sessions args')
        "stop_session" (execute-stop-session args')
        "reload_deps" (execute-reload-deps args')
        "runtime_status" (execute-runtime-status args')
        (throw (ex-info (str "Unknown tool: " name) {:tool name}))))))

(defn- handle-request [{:keys [jsonrpc method params id] :as req}]
  (log-debug ">>" (pr-str req))
  (try
    (case method
      "initialize"
      (send-result id {:protocolVersion "2024-11-05"
                       :capabilities {:tools {}}
                       :serverInfo server-info})

      "notifications/initialized"
      nil  ;; notification — no response

      "tools/list"
      (send-result id {:tools tools})

      "tools/call"
      (let [{:keys [name arguments]} params
            result (execute-tool name arguments)]
        (send-result id result))

      ;; default
      (when id (send-error id -32601 (str "Method not found: " method))))
    (catch clojure.lang.ExceptionInfo e
      (when id (send-result id (mcp-error (ex-message e)))))
    (catch Exception e
      (log-error "handler exception:" (.getMessage e))
      (when id (send-result id (mcp-error (.getMessage e)))))))

(def parent-pid
  (let [parent (.parent (java.lang.ProcessHandle/current))]
    (when (.isPresent parent)
      (.pid (.get parent)))))

(defn- start-parent-watchdog!
  "Exit if the parent process (Claude Code) is gone. Prevents stale MCP
   servers accumulating across sessions when stdio is left dangling."
  []
  (when parent-pid
    (future
      (try
        (loop []
          (Thread/sleep 5000)
          (let [handle (java.lang.ProcessHandle/of parent-pid)]
            (if (and (.isPresent handle)
                     (.isAlive (.get handle)))
              (recur)
              (do
                (log-info "Parent" parent-pid "is gone — exiting to avoid orphaning.")
                (System/exit 0)))))
        (catch Exception e
          (log-error "Watchdog crashed:" (.getMessage e)))))))

(defn -main
  "Serve newline-delimited MCP JSON-RPC on standard I/O."
  []
  (log-info "Seon MCP server starting; project=" project-root)
  (start-parent-watchdog!)
  (let [reader (BufferedReader. *in*)]
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (str/blank? line)
          (try
            (let [req (json/parse-string line true)]
              (handle-request req))
            (catch Exception e
              (log-error "parse error:" (.getMessage e) "line:" line))))
        (recur)))))
