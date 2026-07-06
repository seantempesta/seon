(ns seon.agent.shell-test
  "Envelope-contract tests for `seon.agent.shell/run` + `py-run`.

   The contract under test:

   1. `run` NEVER rejects — every outcome RESOLVES to a
      :seon.agent.shell/run-response envelope (errors are values).
   2. ok? = the process RAN — a NON-ZERO exit is ok? true with the real
      :seon.agent.shell/exit (exit code is data, not an error).
   3. Timeout → ok? true, :seon.agent.shell/timed-out? true, exit sentinel 143.
   4. Gate: SEON_SHELL unset = default-deny envelope; a :seon.agent.shell/cwd
      outside the seon.agent.fs allowlist = denial envelope; an
      allowlisted cwd is honored.
   5. Output discipline: stdout beyond :seon.agent.shell/max-output-tokens is
      clipped with HONEST metadata (full-size :seon.agent.shell/out-tokens,
      :seon.agent.shell/truncated? true, a :seon.agent.shell/hint naming the knobs).
   6. stdin is piped as DATA and always closed (a stdin-reader like
      `cat` sees EOF instead of hanging); `py-run` ships Python source
      via `python3 -` stdin — no shell concatenation, no quoting games.

   Fixtures are hermetic + pid-scoped: a tmp/shell-test-<pid>/ dir for
   cwd gating; seon.agent.fs config and the SEON_SHELL env var are SAVED
   before each test and RESTORED after — live pod config is untouched.
   Child commands use js/process.execPath (this very node binary), `cat`,
   and `python3` — no repo state, no network."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [seon.agent.ctx.jobs]
    [seon.agent.fs :as fs]
    [seon.agent.fs.internal :as fs-int]
    [seon.agent.shell :as shell]
    [seon.test.async :refer [settle!]]))

;; ---------------------------------------------------------------------------
;; Fixture — pid-scoped tmp dir + scoped fs allowlist + SEON_SHELL grant.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/shell-test-" (.-pid js/process))))

(def ^:private node-bin (.-execPath js/process))

(defonce ^:private !saved-fs-config (atom nil))
(defonce ^:private !saved-shell-env (atom nil))

(defn- setup! []
  (.rmSync nfs fixture-dir #js {:recursive true :force true})
  (.mkdirSync nfs fixture-dir #js {:recursive true})
  (reset! !saved-fs-config @fs-int/!config)
  (fs/configure! {:seon.agent.fs/allowed-roots [fixture-dir]
                  :seon.agent.fs/read-only?    true})
  (reset! !saved-shell-env (aget (.-env js/process) "SEON_SHELL"))
  (aset (.-env js/process) "SEON_SHELL" "1"))

(defn- teardown! []
  (fs/configure! @!saved-fs-config)
  (if-some [v @!saved-shell-env]
    (aset (.-env js/process) "SEON_SHELL" v)
    (js-delete (.-env js/process) "SEON_SHELL"))
  (.rmSync nfs fixture-dir #js {:recursive true :force true}))

(use-fixtures :each {:before setup! :after teardown!})

(defn- resolves!
  "Attach a .catch that FAILS the test — run's contract says it resolves
   on every path."
  [p]
  (.catch p (fn [err]
              (is false (str "shell verb REJECTED — envelope contract "
                             "violated: " err))
              ::rejected)))

;; ---------------------------------------------------------------------------
;; 1. Runs — exit 0, both streams captured, honest token totals.
;; ---------------------------------------------------------------------------

(deftest exit-zero-captures-both-streams
  (async done
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd  node-bin
                      :seon.agent.shell/args ["-e" "process.stdout.write('hi-out'); process.stderr.write('hi-err')"]}))
        (.then (fn [{ok?    :seon.agent.shell/ok?
                     exit   :seon.agent.shell/exit
                     out    :seon.agent.shell/out
                     err    :seon.agent.shell/err
                     ot     :seon.agent.shell/out-tokens
                     to?    :seon.agent.shell/timed-out?
                     trunc? :seon.agent.shell/truncated?}]
                 (is (true? ok?))
                 (is (= 0 exit))
                 (is (= "hi-out" out))
                 (is (= "hi-err" err))
                 (is (= 1 ot) "honest full-stdout token estimate (6 chars / 4)")
                 (is (false? to?))
                 (is (false? trunc?))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 2. Non-zero exit is DATA — ok? true, the real exit code, output kept.
;; ---------------------------------------------------------------------------

(deftest non-zero-exit-is-ok-with-exit-as-data
  (async done
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd  node-bin
                      :seon.agent.shell/args ["-e" "process.stdout.write('partial'); process.exit(3)"]}))
        (.then (fn [{ok?  :seon.agent.shell/ok?
                     exit :seon.agent.shell/exit
                     out  :seon.agent.shell/out}]
                 (is (true? ok?) "the process RAN — non-zero exit is not ok? false")
                 (is (= 3 exit) "the real exit code, top-level, as data")
                 (is (= "partial" out) "output before the exit is delivered")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 3. Timeout — SIGTERM, timed-out? authoritative, exit sentinel 143.
;; ---------------------------------------------------------------------------

(deftest timeout-flags-timed-out-with-sentinel-exit
  (async done
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd        node-bin
                      :seon.agent.shell/args       ["-e" "setTimeout(function(){}, 60000)"]
                      :seon.agent.shell/timeout-ms 300}))
        (.then (fn [{ok?  :seon.agent.shell/ok?
                     to?  :seon.agent.shell/timed-out?
                     exit :seon.agent.shell/exit}]
                 (is (true? ok?) "it RAN (and was killed) — still the ran-envelope")
                 (is (true? to?) "timed-out? is the authoritative flag")
                 (is (= 143 exit) "deterministic 128+SIGTERM sentinel")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4a. Gate — SEON_SHELL unset = default-deny error value.
;; ---------------------------------------------------------------------------

(deftest ungranted-is-denied-with-guiding-error
  (async done
    (js-delete (.-env js/process) "SEON_SHELL") ; fixture :after restores
    (-> (resolves! (shell/run {:seon.agent.shell/cmd "true"}))
        (.then (fn [{ok? :seon.agent.shell/ok?
                     msg :seon.error/message}]
                 (is (false? ok?))
                 (is (re-find #"SEON_SHELL" msg) "names the host grant")
                 (is (re-find #"default-deny" msg))
                 (is (false? (:seon.agent.shell/granted? (shell/grants)))
                     "grants reports the same truth")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4b. Gate — cwd outside the fs allowlist is denied; inside is honored.
;; ---------------------------------------------------------------------------

(deftest cwd-outside-allowlist-is-denied
  (async done
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd node-bin
                      :seon.agent.shell/args ["-e" "1"]
                      :seon.agent.shell/cwd (.resolve npath "src")}))
        (.then (fn [{ok? :seon.agent.shell/ok?
                     msg :seon.error/message}]
                 (is (false? ok?))
                 (is (re-find #"cwd" msg))
                 (is (re-find #"seon\.agent\.fs/configure!" msg)
                     "names the fix — the fs allowlist gates shell reach")))
        (settle! done))))

(deftest cwd-inside-allowlist-is-honored
  (async done
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd  node-bin
                      :seon.agent.shell/args ["-e" "process.stdout.write(process.cwd())"]
                      :seon.agent.shell/cwd  fixture-dir}))
        (.then (fn [{ok? :seon.agent.shell/ok?
                     out :seon.agent.shell/out}]
                 (is (true? ok?))
                 (is (str/includes? out (str "shell-test-" (.-pid js/process)))
                     "child actually ran in the granted cwd")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 5. Output is FULL data, no verb-level token cap — display economy is the
;;    render layer's. Only bound = the ~2MB/stream RAM ceiling.
;; ---------------------------------------------------------------------------

(deftest output-is-returned-in-full-with-honest-tokens
  (async done
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd  node-bin
                      :seon.agent.shell/args ["-e" "process.stdout.write('x'.repeat(40000))"]}))
        (.then (fn [{ok?    :seon.agent.shell/ok?
                     out    :seon.agent.shell/out
                     ot     :seon.agent.shell/out-tokens
                     trunc? :seon.agent.shell/truncated?}]
                 (is (true? ok?))
                 (is (= 40000 (count out)) "the FULL stream is returned — no verb-level clip")
                 (is (= 10000 ot) "HONEST full-stdout token size (40000 chars / 4)")
                 (is (false? trunc?) "well under the 2MB RAM ceiling — nothing dropped")))
        (settle! done))))

(deftest over-ram-ceiling-truncates-honestly-with-hint
  (async done
    ;; > 2MB on one stream → Node kills the child at maxBuffer; the captured
    ;; head is the answer, truncated? true, hint points at run-bg!.
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd  node-bin
                      :seon.agent.shell/args ["-e" "process.stdout.write('x'.repeat(2500000))"]}))
        (.then (fn [{ok?    :seon.agent.shell/ok?
                     trunc? :seon.agent.shell/truncated?
                     hint   :seon.agent.shell/hint}]
                 (is (true? ok?) "the process RAN — the partial head is still the answer")
                 (is (true? trunc?) "the 2MB capture ceiling was hit")
                 (is (string? hint))
                 (is (re-find #"run-bg!" hint) "hint points at the unbounded-stream escape")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 6. Could-not-run — binary not found is an error VALUE.
;; ---------------------------------------------------------------------------

(deftest missing-binary-is-error-value
  (async done
    (-> (resolves! (shell/run {:seon.agent.shell/cmd "seon-no-such-binary-xyz"}))
        (.then (fn [{ok? :seon.agent.shell/ok?
                     msg :seon.error/message}]
                 (is (false? ok?) "could NOT run — this is the ok? false branch")
                 (is (re-find #"not found" msg))))
        (settle! done))))

(deftest blank-cmd-is-error-value
  (async done
    (-> (resolves! (shell/run {:seon.agent.shell/cmd " "}))
        (.then (fn [{ok? :seon.agent.shell/ok?
                     msg :seon.error/message}]
                 (is (false? ok?))
                 (is (re-find #"cmd" msg))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 7. stdin — piped as data, always closed (no hang on stdin-readers).
;; ---------------------------------------------------------------------------

(deftest stdin-is-piped-and-closed
  (async done
    (-> (resolves! (shell/run {:seon.agent.shell/cmd   "cat"
                               :seon.agent.shell/stdin "hello stdin"}))
        (.then (fn [{ok?  :seon.agent.shell/ok?
                     exit :seon.agent.shell/exit
                     out  :seon.agent.shell/out}]
                 (is (true? ok?))
                 (is (= 0 exit))
                 (is (= "hello stdin" out)
                     "stdin reached the child and EOF let it exit")))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 8. py-run — Python source via `python3 -` stdin; same envelope.
;; ---------------------------------------------------------------------------

(deftest py-run-executes-source-via-stdin
  (async done
    (-> (resolves! (shell/py-run {:seon.agent.shell/source "import sys\nprint(21 * 2)"}))
        (.then (fn [{ok?  :seon.agent.shell/ok?
                     exit :seon.agent.shell/exit
                     out  :seon.agent.shell/out}]
                 (is (true? ok?))
                 (is (= 0 exit))
                 (is (= "42\n" out) "multi-line source ran; stdout captured")))
        (settle! done))))

(deftest py-run-nonzero-exit-is-data
  (async done
    (-> (resolves! (shell/py-run {:seon.agent.shell/source "import sys\nsys.exit(7)"}))
        (.then (fn [{ok?  :seon.agent.shell/ok?
                     exit :seon.agent.shell/exit}]
                 (is (true? ok?) "same ok?-=-RAN refinement as run")
                 (is (= 7 exit))))
        (settle! done))))

(deftest py-run-source-is-data-not-shell
  ;; Shell metacharacters in the SOURCE are inert — it travels as stdin
  ;; data, never through a shell line.
  (async done
    (-> (resolves!
          (shell/py-run {:seon.agent.shell/source "print('a;`whoami`;$(id) \"quoted\"')"}))
        (.then (fn [{ok?  :seon.agent.shell/ok?
                     exit :seon.agent.shell/exit
                     out  :seon.agent.shell/out}]
                 (is (true? ok?))
                 (is (= 0 exit))
                 (is (= "a;`whoami`;$(id) \"quoted\"\n" out)
                     "metacharacters printed literally — no shell touched them")))
        (settle! done))))

(deftest py-run-blank-source-is-error-value
  (async done
    (-> (resolves! (shell/py-run {:seon.agent.shell/source " "}))
        (.then (fn [{ok? :seon.agent.shell/ok?
                     msg :seon.error/message}]
                 (is (false? ok?))
                 (is (re-find #"source" msg))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 9. Background jobs — run-bg! / job-status / job-output (full + ::since) /
;;    job-stop!, plus the derived :jobs context section. Volatile table.
;; ---------------------------------------------------------------------------

(defn- poll-until
  "Resolve when `(pred (job-status id))` holds, or after ~4s. A test helper —
   the job table has no promise, so poll it."
  [id pred]
  (js/Promise.
    (fn [resolve _]
      (let [tries (atom 0)
            step  (fn step []
                    (let [st (shell/job-status {:seon.agent.shell/job-id id})]
                      (if (or (pred st) (> @tries 40))
                        (resolve st)
                        (do (swap! tries inc)
                            (js/setTimeout step 100)))))]
        (step)))))

(deftest run-bg-launches-polls-and-pages-output
  (async done
    (let [{ok?   :seon.agent.shell/ok?
           id    :seon.agent.shell/job-id
           state :seon.agent.shell/state}
          (shell/run-bg! {:seon.agent.shell/cmd  "bash"
                          :seon.agent.shell/args ["-c" "echo l1; sleep 1; echo l2"]})]
      (is (true? ok?) "launch ok")
      (is (string? id))
      (is (= :running state) "starts running")
      (-> (poll-until id #(= :exited (:seon.agent.shell/state %)))
          (.then (fn [st]
                   (is (= :exited (:seon.agent.shell/state st)))
                   (is (= 0 (:seon.agent.shell/exit st)) "exit code captured")
                   ;; full output
                   (let [full (shell/job-output {:seon.agent.shell/job-id id})]
                     (is (= "l1\nl2\n" (:seon.agent.shell/content full))
                         "FULL captured stdout, uncapped")
                     (is (false? (:seon.agent.shell/truncated? full)))
                     ;; ::since returns only new output past the offset
                     (let [tail (shell/job-output {:seon.agent.shell/job-id id
                                                   :seon.agent.shell/since 3})]
                       (is (= "l2\n" (:seon.agent.shell/content tail))
                           "::since 3 skips the first line already seen")
                       (is (= 6 (:seon.agent.shell/next-since tail))
                           "next-since = end offset for the next poll")))
                   ;; the derived :jobs section renders this job with the handle
                   (let [blk (seon.agent.ctx.jobs/jobs-block {})]
                     (is (str/includes? blk id) "section names the job")
                     (is (str/includes? blk "job-output") "…with the read-more handle"))))
          (settle! done)))))

(deftest job-stop-sigterms-a-running-job
  (async done
    (let [{id :seon.agent.shell/job-id}
          (shell/run-bg! {:seon.agent.shell/cmd  "bash"
                          :seon.agent.shell/args ["-c" "sleep 30"]})
          r  (shell/job-stop! {:seon.agent.shell/job-id id})]
      (is (true? (:seon.agent.shell/ok? r)))
      (is (= :stopped (:seon.agent.shell/state r)) "marked stopped immediately")
      (-> (poll-until id #(not= :running (:seon.agent.shell/state %)))
          (.then (fn [st]
                   (is (not= :running (:seon.agent.shell/state st))
                       "the child actually terminated")))
          (settle! done)))))

(deftest job-verbs-on-unknown-id-are-guiding-values
  (async done
    (let [bad {:seon.agent.shell/job-id "job-nope"}]
      (doseq [r [(shell/job-status bad) (shell/job-output bad) (shell/job-stop! bad)]]
        (is (false? (:seon.agent.shell/ok? r)))
        (is (re-find #"no background job" (:seon.error/message r))))
      ;; empty section renders nothing
      (done))))
