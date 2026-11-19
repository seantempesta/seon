(ns seon.switchover
  "Atomic, fault-isolated core binary switch-over (`bin/build seon` -> `--self-test` -> graceful `--take-over`):
   - Ordinary Clojure changes NEVER need a rebuild (handled live via `:transactor` hot-reload + `ForkParent` `:advance-image`).
   - When a `:boot-affected?` change merges (`seon.main`, `seon.fork` parent loop, `seon.store/open!`,
     `deps.edn`, `bin/build`, `native/*`), `run-switch-over!` builds a staged candidate binary, verifies it
     with `--self-test` BEFORE touching `target/seon`, and spawns a detached `--take-over` successor so the
     running daemon drains in-flight turns/writes cleanly and hands over the port with zero data loss.
   - If build or `--self-test` fails, the running server stays 100% untouched."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [jolt.fibers :as fibers]))

(defn- sh-fiber [& args]
  (if (fibers/in-fiber?)
    (apply sh/sh args)
    (fibers/join (fibers/spawn #(apply sh/sh args)))))

(defn build-and-verify!
  "Runs `bin/build seon` in `repo-dir` (which builds into an isolated `target/seon.<id>.new`,
   runs `--self-test` on that candidate, and only atomically moves it to `target/seon` on pass)."
  [repo-dir]
  (let [build-bin (str repo-dir "/bin/build")]
    (if-not (.isFile (io/file build-bin))
      {:ok? false :stage :build :error (str "Missing build script: " build-bin)}
      (let [{:keys [exit out err]} (sh-fiber build-bin "seon" :dir repo-dir)]
        (if (zero? exit)
          {:ok? true :stage :build :out out}
          {:ok? false
           :stage :build
           :exit exit
           :error (str "bin/build seon failed (exit " exit "): "
                       (str/trim (if (str/blank? err) (str out) (str err))))})))))

(defn spawn-successor!
  "Spawns detached `target/seon --take-over <port>` inheriting environment variables (API keys,
   git sync flags) and appending stdout/stderr to `<repo-dir>/runs/server.log`."
  [{:keys [repo-dir store-path port]}]
  (let [bin-path (str repo-dir "/target/seon")
        log-dir (io/file repo-dir "runs")
        log-path (str repo-dir "/runs/server.log")]
    (if-not (.canExecute (io/file bin-path))
      {:ok? false :stage :spawn :error (str "Candidate binary missing or not executable: " bin-path)}
      (do
        (.mkdirs log-dir)
        (let [shell-q (fn [^String s] (str "'" (str/replace s "'" "'\\''") "'"))
              shell-bin (if (.canExecute (io/file "/bin/zsh")) "/bin/zsh" "/bin/bash")
              env-file (io/file repo-dir "bin/env")
              env-prefix (if (.isFile env-file)
                           (str "source " (shell-q (.getPath env-file)) " && ")
                           "")
              cmd (str "cd " (shell-q repo-dir) " && "
                       env-prefix
                       "SEON_BINARY=1 nohup " (shell-q bin-path)
                       " --take-over " (long port)
                       " --repo " (shell-q repo-dir)
                       " --store " (shell-q store-path)
                       " >> " (shell-q log-path) " 2>&1 </dev/null & "
                       "pid=$!; sleep 0.15; if kill -0 \"$pid\" 2>/dev/null; then echo \"$pid\"; else exit 1; fi")
              {:keys [exit out err]} (sh-fiber shell-bin "-c" cmd)
              pid (when (zero? exit) (parse-long (str/trim (str out))))]
          (if (and (zero? exit) pid)
            {:ok? true :stage :spawn :port (long port) :pid pid :log log-path}
            {:ok? false
             :stage :spawn
             :exit exit
             :error (str "Failed to spawn successor (exit " exit "): "
                         (str/trim (if (str/blank? err) (str out) (str err))))}))))))

(defn run-switch-over!
  "Executes the full atomic switch-over pipeline (`build-fn` -> `spawn-fn`).
   If `build-fn` fails, `spawn-fn` is NEVER invoked and the running daemon stays 100% untouched."
  [{:keys [repo-dir store-path port build-fn spawn-fn]
    :or {build-fn build-and-verify!
         spawn-fn spawn-successor!}}]
  (cond
    (str/blank? (str repo-dir))
    {:ok? false :stage :validate :error "Missing :repo-dir for switch-over"}

    (not (and (number? port) (pos? (long port))))
    {:ok? false :stage :validate :error (str "Switch-over requires a bound HTTP port > 0, got: " port)}

    :else
    (let [b-res (try
                  (build-fn repo-dir)
                  (catch Throwable ex
                    {:ok? false :stage :build :error (or (.getMessage ex) (str ex))}))]
      (if-not (:ok? b-res)
        b-res
        (let [s-res (try
                      (spawn-fn {:repo-dir repo-dir :store-path store-path :port (long port)})
                      (catch Throwable ex
                        {:ok? false :stage :spawn :error (or (.getMessage ex) (str ex))}))]
          (if (:ok? s-res)
            (assoc s-res :swapping? true)
            s-res))))))
