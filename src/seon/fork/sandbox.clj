(ns seon.fork.sandbox
  "In-process macOS Seatbelt (`sandbox_init`) kernel sandbox for COW forked agent & test workers.
   Denies writes under canonicalized `repo-dir` while allowing writes under canonicalized `store-path`
   (Seatbelt last-match-wins), blocking disk/Git mutations at the OS kernel boundary while preserving
   100% COW `fork-alone` and LMDB branch writes."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [seon.store :as store]))

(defn macos?
  "True when running on macOS/Darwin where Seatbelt `sandbox_init` is available."
  {:malli/schema [:=> [:cat] :boolean]
   :cost/ticks 50000}
  []
  (let [os (str/lower-case (str (System/getProperty "os.name" "")))]
    (or (str/includes? os "mac") (str/includes? os "darwin"))))

(ffi/load-library)

(when (macos?)
  (ffi/load-library "/usr/lib/libSystem.B.dylib"))

(ffi/defcfn c-sandbox-init "sandbox_init" [:string :uint64 :pointer] :int)

(ffi/defcfn c-sandbox-free-error "sandbox_free_error" [:pointer] :void)

(ffi/defcfn c-waitpid "waitpid" [:int :pointer :int] :int)

(defonce ^:private ^:seon.flow/edge applied-sandbox (atom nil))

(defn current-sandbox
  "Returns the sandbox metadata applied to the current OS process (or inherited across fork), or nil."
  {:malli/schema [:=> [:cat] [:maybe :map]], :cost/ticks 50000, :seon.flow/edge true}
  []
  @applied-sandbox)

(defn escape-sbpl-string
  "Escapes a filesystem path string for safe embedding inside a Seatbelt SBPL double-quoted literal."
  {:malli/schema [:=> [:cat :string] :string]
   :cost/ticks 500000}
  [^String s]
  (-> s
      (str/replace "\u0000" "")
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")))

(defn canonical-path
  "Resolves a non-blank path string to its canonical realpath via `java.io.File/.getCanonicalPath`
   (Seatbelt matches resolved realpaths such as `/private/tmp`). Returns nil for nil/blank inputs."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]
   :cost/ticks 5000000}
  [p]
  (when (and (string? p) (not (str/blank? p)))
    (let [clean (str/replace p "\u0000" "")]
      (when-not (str/blank? clean)
        (.getCanonicalPath (java.io.File. ^String clean))))))

(defn- ancestor-path? [parent child]
  (or (= parent child)
      (str/starts-with? child (if (str/ends-with? parent "/") parent (str parent "/")))))

(defn build-seatbelt-profile
  "Builds a macOS Seatbelt SBPL profile denying `file-write*` under canonicalized `repo-dir` while
   allowing `file-write*` under canonicalized `store-path` (last-match-wins). Skips the repo deny
   when `repo-dir` is nil/blank or canonicalizes equal to `store-path`."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :string]
   :cost/ticks 5000000}
  [repo-dir store-path]
  (let [c-repo (canonical-path repo-dir)
        c-store (canonical-path store-path)
        deny-repo? (and c-repo (not= c-repo c-store))
        allow-store? (and deny-repo? c-store (not (ancestor-path? c-store c-repo)))]
    (str "(version 1)\n"
         "(allow default)\n"
         (when deny-repo?
           (str "(deny file-write* (subpath \"" (escape-sbpl-string c-repo) "\"))\n"))
         (when allow-store?
           (str "(allow file-write* (subpath \"" (escape-sbpl-string c-store) "\"))\n")))))

(defn resolve-sandbox-plan
  "Pure planner deciding whether to apply the macOS worker kernel sandbox for `{:sandbox? :macos? :repo-dir :store-path}`."
  {:malli/schema [:=> [:cat [:map
                             [:sandbox? {:optional true} [:maybe :boolean]]
                             [:macos? {:optional true} [:maybe :boolean]]
                             [:repo-dir {:optional true} [:maybe :string]]
                             [:store-path {:optional true} [:maybe :string]]]]
                  :map]
   :cost/ticks 5000000}
  [opts]
  (let [mac-opt (:macos? opts)
        sb-opt (:sandbox? opts)
        os-ok? (if (some? mac-opt) (boolean mac-opt) (macos?))
        enabled? (if (some? sb-opt) (boolean sb-opt) true)
        c-repo (canonical-path (:repo-dir opts))
        c-store (canonical-path (:store-path opts))]
    (cond
      (not enabled?)
      {:apply? false :reason :disabled :repo-dir c-repo :store-path c-store :profile nil}

      (not os-ok?)
      {:apply? false :reason :unsupported-os :repo-dir c-repo :store-path c-store :profile nil}

      (nil? c-repo)
      {:apply? false :reason :no-repo-dir :repo-dir nil :store-path c-store :profile nil}

      (= c-repo c-store)
      {:apply? false :reason :repo-equals-store :repo-dir c-repo :store-path c-store :profile nil}

      :else
      {:apply? true
       :reason :macos-seatbelt
       :repo-dir c-repo
       :store-path c-store
       :profile (build-seatbelt-profile c-repo c-store)})))

(defn ^:seon.flow/edge apply-seatbelt-profile!
  "Applies a Seatbelt SBPL `profile` string to the CURRENT OS process via `sandbox_init`.
   NEVER call in the coordinator or ForkParent — ONLY inside a forked worker child.
   Fail-closed: throws `ex-info` with the Seatbelt diagnostic if `sandbox_init` returns non-zero."
  {:malli/schema [:=> [:cat :string] :map]}
  [profile]
  (when-not (macos?)
    (throw (ex-info "Seatbelt sandbox_init is only available on macOS"
                    {:error :seon.sandbox/unsupported-os})))
  (ffi/with-out [err-pp :pointer]
    (let [rc (long (c-sandbox-init profile 0 err-pp))
          ep (ffi/read err-pp :pointer)
          err-msg (when-not (ffi/null? ep)
                    (try
                      (ffi/ptr->string ep)
                      (finally
                        (c-sandbox-free-error ep))))]
      (if (zero? rc)
        {:ok? true :rc 0}
        (throw (ex-info (str "Kernel worker sandbox_init failed (rc=" rc "): " (or err-msg "unknown error"))
                        {:error :seon.sandbox/init-failed
                         :rc rc
                         :sandbox-error err-msg
                         :profile profile}))))))

(defn ^:seon.flow/edge apply-worker-sandbox!
  "Applies the kernel worker sandbox inside a forked worker child (`(zero? pid)`).
   Extracts `:store-path` from `:cfg` when omitted. Fail-closed on `sandbox_init` errors.
   If this process already inherited a Seon worker sandbox across fork (e.g. nested E2E test),
   returns `{:applied? false :already-sandboxed? true :inherited ...}`."
  {:malli/schema [:=> [:cat :map] :map], :doc "Applies the kernel worker sandbox inside a forked worker child (`(zero? pid)`).\n   Extracts `:store-path` from `:cfg` when omitted. Fail-closed on `sandbox_init` errors.\n   If this process already inherited a Seon worker sandbox across fork (e.g. nested E2E test),\n   returns `{:applied? false :already-sandboxed? true :inherited ...}`. (enforced at fork boundary)."}
  [{:keys [repo-dir store-path cfg sandbox?]}]
  (let [eff-store (or store-path (get-in cfg [:store :path]))
        plan (resolve-sandbox-plan {:sandbox? sandbox?
                                    :repo-dir repo-dir
                                    :store-path eff-store})]
    (if-not (:apply? plan)
      (assoc plan :applied? false)
      (if-let [existing @applied-sandbox]
        (assoc plan :applied? false :already-sandboxed? true :inherited existing)
        (do
          (apply-seatbelt-profile! (:profile plan))
          (let [applied (assoc plan :applied? true)]
            (reset! applied-sandbox (select-keys applied [:repo-dir :store-path :reason]))
            applied))))))

(defn ^:seon.flow/edge wrap-fork-out
  "Wraps `fork-out` with an instance-scoped channel attaching `:repo-dir` and effective `:sandbox?`
   (honoring explicit `sandbox-opt`, `:seon.config/sandbox-workers?`, defaulting true) onto
   `:fork-agent` and `:fork-test` messages without mutating shared `ForkParent` state."
  {:malli/schema [:=> [:cat :seon.host/chan [:maybe :seon.host/conn] [:maybe :string] [:maybe :boolean]] :seon.host/chan]}
  [fork-out conn repo-dir sandbox-opt]
  (let [xf (map (fn [m]
                  (if (and (map? m) (#{:fork-agent :fork-test} (:op m)))
                    (let [sb? (if (contains? m :sandbox?)
                                (:sandbox? m)
                                (if (some? sandbox-opt)
                                  (boolean sandbox-opt)
                                  (let [cv (when conn
                                             (store/config-val @conn (:agent-id m) :seon.config/sandbox-workers?))]
                                    (if (some? cv) (boolean cv) true))))]
                      (cond-> (assoc m :sandbox? sb?)
                        (and repo-dir (nil? (:repo-dir m))) (assoc :repo-dir repo-dir)))
                    m)))
        ch (a/chan 128 xf)]
    (a/pipe ch fork-out false)
    ch))

(defn ^:seon.flow/edge self-test-sandbox!
  "Fast `--self-test` verifier: forks an isolated child, applies `apply-worker-sandbox!` against
   a temp `/tmp/seon-sb-selftest-*` repo + `runs/store`, proves store writes succeed and repo writes
   fail (`EPERM`), reaps the child, and cleans up the temp dir. Never sandboxes the caller process."
  {:malli/schema [:=> [:cat] :map]}
  []
  (cond
    (not (macos?))
    {:ok? true :skipped? true :reason :unsupported-os}

    @applied-sandbox
    {:ok? true :already-sandboxed? true :inherited @applied-sandbox}

    :else
    (let [base (str "/tmp/seon-sb-selftest-" (System/nanoTime))
          store-dir (str base "/runs/store")]
      (try
        (.mkdirs (io/file base "src"))
        (.mkdirs (io/file store-dir))
        (let [[end fd] (remote/remote-pair {:pump :fiber :buf 4})
              pid (jolt.host/fork-alone 1000)]
          (cond
            (zero? pid)
            (do (remote/close-streams-but! #{fd})
                (let [mine (remote/open fd {:pump :fiber :buf 4})]
                  (try
                    (let [sb (apply-worker-sandbox! {:repo-dir base :store-path store-dir :sandbox? true})
                          store-ok? (try
                                      (spit (io/file store-dir "probe.txt") "ok")
                                      (= "ok" (slurp (io/file store-dir "probe.txt")))
                                      (catch Throwable _ false))
                          repo-blocked? (try
                                          (spit (io/file base "src/evil.clj") "blocked")
                                          false
                                          (catch Throwable _ true))]
                      (a/>!! (:out mine) {:ok? (boolean (and (:applied? sb) store-ok? repo-blocked?))
                                          :applied? (boolean (:applied? sb))
                                          :store-write-ok? store-ok?
                                          :repo-write-blocked? repo-blocked?}))
                    (catch Throwable ex
                      (a/>!! (:out mine) {:ok? false :error (or (.getMessage ex) (str ex))})))
                  (a/close! (:out mine))
                  (a/<!! (:sent mine))
                  (System/exit 0)))

            (neg? pid)
            (do (remote/close! end)
                (remote/close-fd! fd)
                {:ok? false :error :fork-refused :code pid})

            :else
            (do (remote/close-fd! fd)
                (let [[res _] (a/alts!! [(:in end) (a/timeout 5000)])]
                  (remote/close! end)
                  (ffi/with-alloc [st-buf 4]
                    (c-waitpid pid st-buf 0))
                  (or res {:ok? false :error :sandbox-self-test-timeout})))))
        (finally
          (store/rm-rf! (io/file base)))))))
