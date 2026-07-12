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
   5. Output discipline: stdout is returned in full with canonical token
      metadata; crossing the private RAM guard is marked honestly and the
      recovery hint points to the background-stream path.
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
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.run :as run]
    [seon.agent.shell :as shell]
    [seon.agent.testrun :as testrun]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
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
              (is false (str "shell function REJECTED — envelope contract "
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
                 (is (= (tokens/estimate out) ot)
                     "stdout metadata uses the canonical token estimator")
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
;; 5. Output is FULL data, no function-level token cap — display economy is the
;;    render layer's. A private per-stream RAM guard remains.
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
                 (is (= 40000 (count out)) "the FULL stream is returned — no function-level clip")
                 (is (= (tokens/estimate out) ot)
                     "full-stream metadata uses the canonical token estimator")
                 (is (false? trunc?) "the private RAM guard did not drop output")))
        (settle! done))))

(deftest over-ram-ceiling-truncates-honestly-with-hint
  (async done
    ;; Exceed the private maxBuffer guard: the captured head is the answer,
    ;; truncated? is true, and the hint points at run-bg!.
    (-> (resolves!
          (shell/run {:seon.agent.shell/cmd  node-bin
                      :seon.agent.shell/args ["-e" "process.stdout.write('x'.repeat(2500000))"]}))
        (.then (fn [{ok?    :seon.agent.shell/ok?
                     trunc? :seon.agent.shell/truncated?
                     hint   :seon.agent.shell/hint}]
                 (is (true? ok?) "the process RAN — the partial head is still the answer")
                 (is (true? trunc?) "the private capture ceiling was hit")
                 (is (string? hint))
                 (is (not (re-find #"(?i)\d+\s*(?:chars?|characters?|bytes?|[kmg]b)\b"
                                   hint))
                     "the generated recovery hint does not expose raw size units")
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

(deftest job-functions-on-unknown-id-are-guiding-values
  (async done
    (let [bad {:seon.agent.shell/job-id "job-nope"}]
      (doseq [r [(shell/job-status bad) (shell/job-output bad) (shell/job-stop! bad)]]
        (is (false? (:seon.agent.shell/ok? r)))
        (is (re-find #"no background job" (:seon.error/message r))))
      ;; empty section renders nothing
      (done))))

(deftest bg-jobs-are-scoped-per-agent-no-cross-agent-leak
  ;; OBS-1: a background job is a per-agent volatile runtime artifact, so an
  ;; agent must see (list/status/output/stop) ONLY the jobs IT launched. Agent
  ;; B's job appears to NOT EXIST to agent A — same guiding unknown-job value as
  ;; a truly-absent id, so A's isolation never even leaks B's existence. Two
  ;; 14-char agent ids (the :seon.agent/id shape the section-request validates).
  (async done
    (let [A     "shelltestA0001"
          B     "shelltestB0001"
          bg    (fn [aid] (db/with-agent aid
                            (fn [] (:seon.agent.shell/job-id
                                    (shell/run-bg! {:seon.agent.shell/cmd  "bash"
                                                    :seon.agent.shell/args ["-c" "sleep 30"]})))))
          a-id  (bg A)
          b-id  (bg B)
          ids   (fn [aid] (db/with-agent aid
                            (fn [] (->> (shell/list-jobs) :seon.agent.shell/jobs
                                        (mapv :seon.agent.shell/job-id) set))))]
      ;; 1. list-jobs is scoped — A sees only a-id, B only b-id.
      (is (= #{a-id} (ids A)) "A's list contains ONLY A's job")
      (is (= #{b-id} (ids B)) "B's list contains ONLY B's job")
      ;; 2. A on B's id → the SAME unknown-job envelope as an absent id.
      (db/with-agent A
        (fn []
          (doseq [r [(shell/job-status {:seon.agent.shell/job-id b-id})
                     (shell/job-output {:seon.agent.shell/job-id b-id})
                     (shell/job-stop!  {:seon.agent.shell/job-id b-id})]]
            (is (false? (:seon.agent.shell/ok? r)) "B's job appears not to exist to A")
            (is (re-find #"no background job" (:seon.error/message r))
                "isolation: identical to a truly-unknown id, no distinct 'not yours'"))
          ;; 3. A's OWN id still works for A.
          (is (true? (:seon.agent.shell/ok? (shell/job-status {:seon.agent.shell/job-id a-id})))
              "A's own job is fully readable by A")
          ;; 4. the derived section is scoped too — A's render names a-id, not b-id.
          (let [blk (seon.agent.ctx.jobs/jobs-block {})]
            (is (str/includes? blk a-id) "A's :jobs section shows A's job")
            (is (not (str/includes? blk b-id)) "A's :jobs section HIDES B's job"))))
      ;; B's job is still alive (A's stop was a no-op on a job it can't see).
      (is (= :running (db/with-agent B (fn [] (:seon.agent.shell/state
                                               (shell/job-status {:seon.agent.shell/job-id b-id})))))
          "A could not stop B's job")
      ;; cleanup — stop both children.
      (db/with-agent A (fn [] (shell/job-stop! {:seon.agent.shell/job-id a-id})))
      (db/with-agent B (fn [] (shell/job-stop! {:seon.agent.shell/job-id b-id})))
      (done))))

;; ---------------------------------------------------------------------------
;; 10. Persist-at-exit — a BACKGROUND pytest job records a testrun datom when
;;     it finishes, so the complete-gate is NOT blind to bg tests (D-GATE-BG).
;;     End-to-end: a real spawned fake-pytest child (basename "pytest" so
;;     testrun/pytest-argv? recognizes it) → close handler → testrun/record!
;;     scoped to the SPAWNING agent → testrun/latest-run → lifecycle/complete.
;; ---------------------------------------------------------------------------

(defn- write-fake-pytest!
  "A tiny executable `pytest` under fixture-dir; `$1 = green` prints a green
   summary + exit 0, else a red short-summary + exit 1. Basename \"pytest\" is
   what testrun/pytest-argv? keys on — so this exercises the real argv gate."
  []
  (let [path (.resolve npath fixture-dir "pytest")]
    (.writeFileSync
      nfs path
      (str "#!/bin/bash\n"
           "if [ \"$1\" = green ]; then\n"
           "  echo 'collected 2 items'\n"
           "  echo '======================== 2 passed in 0.01s ========================'\n"
           "  exit 0\n"
           "fi\n"
           "echo '==================== short test summary info ===================='\n"
           "echo 'FAILED tests/test_x.py::test_a - assert 1 == 2'\n"
           "echo '================== 1 failed, 1 passed in 0.02s =================='\n"
           "exit 1\n")
      #js {:mode 493})                                    ; 0755, executable
    path))

(defn- poll-for
  "Resolve with (thunk) once (pred (thunk)) holds, or after ~5s (a test
   helper — the job table + the testrun datom have no promise to await)."
  [thunk pred]
  (js/Promise.
    (fn [resolve _]
      (let [tries (atom 0)
            step  (fn step []
                    (let [v (thunk)]
                      (if (or (pred v) (> @tries 50))
                        (resolve v)
                        (do (swap! tries inc) (js/setTimeout step 100)))))]
        (step)))))

(deftest bg-pytest-run-persists-testrun-at-exit-and-gates-complete
  (async done
    (let [pytest (write-fake-pytest!)
          aid    "shbgtestagt001"
          latest #(testrun/latest-run @db/*conn* aid)
          ;; run-bg! under the agent scope so start-job! captures aid; poll to
          ;; :exited, then poll the persisted testrun until (pred) holds.
          bg     (fn [args pred]
                   (db/with-agent aid
                     (fn []
                       (let [{id :seon.agent.shell/job-id}
                             (shell/run-bg! {:seon.agent.shell/cmd  pytest
                                             :seon.agent.shell/args args
                                             :seon.agent.shell/cwd  fixture-dir})]
                         (-> (poll-until id #(= :exited (:seon.agent.shell/state %)))
                             (.then (fn [_] (poll-for latest pred))))))))
          complete! (fn ^:async _ []
                      (await (run/open-run! {:seon.agent/id aid
                                             :seon.agent.run/trigger :message}))
                      (await (db/with-agent aid
                               (fn ^:async c [] (await (lifecycle/complete "all pass"))))))]
      (-> (client/open-agent-conn!)
          (.then (fn [conn]
                   (let [prev db/*conn*]
                     (set! db/*conn* conn)
                     (-> (db/transact! {:seon.db/tx-data [{:seon.agent/id aid}
                                                          {:seon.user/id "user"}]})
                         ;; (1) a RED bg run persists a red testrun scoped to aid.
                         (.then (fn [_] (bg ["red"] #(and % (pos? (:seon.agent.testrun/failed %))))))
                         (.then (fn [lr]
                                  (is (some? lr) "a bg pytest run persisted a testrun datom")
                                  (is (= 1 (:seon.agent.testrun/failed lr)) "the red failed count")))
                         ;; (2) the complete-gate REFUSES on the red bg run.
                         (.then (fn [_] (complete!)))
                         (.then (fn [env]
                                  (is (false? (:seon.db/ok? env))
                                      "complete refused — the gate saw the BACKGROUND red run")
                                  (is (str/includes? (:seon.error/message (:seon.db/error env)) "RED")
                                      "verbatim refusal names the RED state")))
                         ;; (3) a later GREEN bg run supersedes → complete allowed.
                         (.then (fn [_]
                                  (bg ["green"] #(and % (zero? (:seon.agent.testrun/failed %))
                                                      (zero? (:seon.agent.testrun/errors %))))))
                         (.then (fn [lr]
                                  (is (zero? (:seon.agent.testrun/failed lr)) "latest is now green")))
                         (.then (fn [_] (complete!)))
                         (.then (fn [r]
                                  (is (= :idle r) "green latest bg run → complete allowed")))
                         ;; (4) a NON-pytest bg job records nothing (latest stays green).
                         (.then (fn [_]
                                  (let [green-eid (:seon.agent.testrun/eid (latest))]
                                    (db/with-agent aid
                                      (fn []
                                        (let [{id :seon.agent.shell/job-id}
                                              (shell/run-bg! {:seon.agent.shell/cmd  "bash"
                                                              :seon.agent.shell/args ["-c" "echo '1 passed'"]
                                                              :seon.agent.shell/cwd  fixture-dir})]
                                          (-> (poll-until id #(= :exited (:seon.agent.shell/state %)))
                                              ;; give any (wrong) record! a chance to land, then assert none did
                                              (.then (fn [_] (poll-for (constantly nil) (constantly false))))
                                              (.then (fn [_]
                                                       (is (= green-eid (:seon.agent.testrun/eid (latest)))
                                                           "a non-pytest bg job persisted NO testrun (latest unchanged)"))))))))))
                         (.finally (fn [] (set! db/*conn* prev)))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
