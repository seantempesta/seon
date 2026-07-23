(ns seon.agent.shell.core
  "Pure shell capability request and response policy."
  (:require
   [clojure.string :as str]
   [seon.ai.tokens :as tokens]))

(def killed-exit 143)

(defn fail
  "Return the flat shell failure envelope."
  ([message] (fail message nil))
  ([message data]
   (cond-> {:seon.agent.shell/ok? false
            :seon.error/message message
            :seon.error/kind :user-input}
     (seq data) (assoc :seon.error/data data))))

(defn ungranted
  "Return the shell default-deny response."
  []
  (fail (str "shell access is not granted (default-deny) — the governing "
             "configuration key :seon.config/shell-enabled? must be true; "
             "the current pod leaf derives that key from SEON_SHELL; "
             "inspect the effective grant with (seon.agent.shell/grants).")
        {:seon.config/key :seon.config/shell-enabled?}))

(defn run-request
  "Normalize a child run using an acquired millisecond timeout config fact."
  [{:seon.agent.shell/keys [cmd args cwd stdin timeout-ms]}
   configuration
   max-output-bytes]
  (if-let [default-timeout-ms
           (:seon.config.shell/default-timeout-ms configuration)]
    (cond-> {:seon.subprocess/cmd (into [cmd] (or args []))
             :seon.subprocess/timeout-ms (or timeout-ms default-timeout-ms)
             :seon.subprocess/max-output-bytes max-output-bytes}
      cwd (assoc :seon.subprocess/cwd cwd)
      (some? stdin) (assoc :seon.subprocess/stdin stdin))
    (fail
     "The shell timeout policy is unavailable; apply the governing config."
     {:seon.config/key :seon.config.shell/default-timeout-ms})))

(defn py-request
  "Specialize a frozen Python request into the ordinary run call shape."
  [{:seon.agent.shell/keys [source cmd args cwd timeout-ms]}]
  (if (str/blank? source)
    (fail (str ":seon.agent.shell/source is required and must be non-blank — "
               "the Python source text is shipped as stdin data."))
    (cond-> {:seon.agent.shell/cmd (or cmd "python3")
             :seon.agent.shell/args (into ["-"] (or args []))
             :seon.agent.shell/stdin source}
      cwd (assoc :seon.agent.shell/cwd cwd)
      timeout-ms (assoc :seon.agent.shell/timeout-ms timeout-ms))))

(defn ran-envelope
  "Interpret a completed subprocess as the frozen public run envelope."
  [exit out err timed-out? truncated?]
  (let [out (str out) err (str err)]
    (cond-> {:seon.agent.shell/ok? true
             :seon.agent.shell/exit exit
             :seon.agent.shell/out out
             :seon.agent.shell/err err
             :seon.agent.shell/out-tokens (tokens/estimate out)
             :seon.agent.shell/err-tokens (tokens/estimate err)
             :seon.agent.shell/timed-out? (boolean timed-out?)
             :seon.agent.shell/truncated? (boolean truncated?)}
      truncated?
      (assoc :seon.agent.shell/hint
             (str "output reached the hard capture ceiling; later output was "
                  "dropped. Use (seon.agent.shell/run-bg! …) and page it "
                  "with job-output.")))))

(defn slice-since
  "Return captured output from a stable character cursor."
  [stream since]
  (let [stream (str stream)
        total (count stream)
        from (min (max 0 (or since 0)) total)]
    {:seon.agent.shell/content (subs stream from)
     :seon.agent.shell/since from
     :seon.agent.shell/next-since total}))
