(ns seon.log-test
  "Tests for file-only logging. Covers:
     - tail reads from the active log file (no ring buffer)
     - tail filters (level, agent, source)
     - NDJSON-EDN file write + readability
     - tail with N less than total
     - file rotation at file-cap
     - tail still works after a simulated pod restart (reconfiguring
       to a new file is the same as restarting against the same path)

   Run interactively via MCP eval:

     (require 'seon.log-test :reload)
     (cljs.test/run-tests 'seon.log-test)"
  (:require
    ["node:fs"   :as fs]
    ["node:path" :as np]
    [cljs.reader :as edn]
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.log :as log]))

;; ============================================================
;; Per-test isolation — each test runs against a fresh tmp file by
;; swapping `:seon.log/file` in `seon.log/!config` so we don't collide
;; with the live pod's log.
;;
;; The log file path used to be a `^:dynamic` Var, but dynvars don't
;; reliably survive `await` boundaries in CLJS over Promises (the
;; runtime is Promise-based; binding frames are not preserved across
;; microtasks). It now lives in the !config atom alongside
;; :seon.log/file-cap and :seon.log/keep. See task-17 handoff doc.
;; ============================================================

(defn- tmp-dir []
  (let [base (or (.. js/process -env -TMPDIR) "/tmp")]
    (.join np base (str "seon-log-test-" (random-uuid)))))

(defn- tmp-file []
  (.join np (tmp-dir) "events.log"))

(defn- cleanup! [path]
  (try (.rmSync fs (.dirname np path) #js {:recursive true :force true})
       (catch :default _ nil)))

(defn- reset-config! []
  ;; Restore default rotation knobs.
  (log/configure! {:seon.log/file-cap (* 5 1024 1024)
                   :seon.log/keep     3}))

(defn- with-log-file*
  "Test helper — set `:seon.log/file` to `path` in !config, run `f`,
   then restore the complete prior config snapshot. `:seon.log/file` is
   ABSENT by default (a test process must never claim the live pod's
   file), so restore resets the snapshot map instead of re-configuring
   a possibly-absent key with nil."
  [path f]
  (let [saved @log/!config]
    (try
      (log/configure! {:seon.log/file path})
      (f)
      (finally (reset! log/!config saved)))))

;; ============================================================
;; Tests
;; ============================================================

(deftest tail-reads-file-newest-first
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (log/info! {:seon.log/source ::probe :seon.log/message "one"})
          (log/info! {:seon.log/source ::probe :seon.log/message "two"})
          (log/info! {:seon.log/source ::probe :seon.log/message "three"})
          (let [out (log/tail {:seon.log/n 10})]
            (is (= 3 (count out)) "all three written entries visible")
            (is (= "three" (:seon.log/message (first out))) "newest first")
            (is (= "one"   (:seon.log/message (last out))) "oldest last"))))
      (finally (cleanup! path)))))

(deftest tail-with-n-less-than-total
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (dotimes [i 10]
            (log/info! {:seon.log/source ::probe
                        :seon.log/message (str "msg " i)}))
          (let [out (log/tail {:seon.log/n 3})]
            (is (= 3 (count out)) "limited to N")
            (is (= "msg 9" (:seon.log/message (first out))) "newest first")
            (is (= "msg 7" (:seon.log/message (last out))) "third-newest last"))))
      (finally (cleanup! path)))))

(deftest tail-filters-by-level
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (log/info!  {:seon.log/source ::p :seon.log/message "i1"})
          (log/error! {:seon.log/source ::p :seon.log/message "e1"})
          (log/info!  {:seon.log/source ::p :seon.log/message "i2"})
          (log/error! {:seon.log/source ::p :seon.log/message "e2"})
          (is (= 2 (count (log/tail {:seon.log/level :error}))))
          (is (= 2 (count (log/tail {:seon.log/level :info}))))
          (is (= "e2" (-> (log/tail {:seon.log/level :error}) first :seon.log/message)))))
      (finally (cleanup! path)))))

(deftest tail-filters-by-agent
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (log/error! {:seon.log/source ::p :seon.log/message "m1"})
          (log/error! {:seon.log/source ::p :seon.log/agent "a1" :seon.log/message "m2"})
          (log/error! {:seon.log/source ::p :seon.log/agent "a2" :seon.log/message "m3"})
          (let [a1 (log/tail {:seon.log/agent "a1"})
                a2 (log/tail {:seon.log/agent "a2"})]
            (is (= 1 (count a1)))
            (is (= "m2" (:seon.log/message (first a1))))
            (is (= 1 (count a2)))
            (is (= "m3" (:seon.log/message (first a2)))))))
      (finally (cleanup! path)))))

(deftest tail-filters-by-source
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (log/info! {:seon.log/source ::alpha :seon.log/message "a"})
          (log/info! {:seon.log/source ::beta  :seon.log/message "b"})
          (let [out (log/tail {:seon.log/source ::beta})]
            (is (= 1 (count out)))
            (is (= "b" (:seon.log/message (first out)))))))
      (finally (cleanup! path)))))

(deftest file-write-is-ndjson-edn
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (log/info!  {:seon.log/source ::probe :seon.log/message "line one"})
          (log/error! {:seon.log/source ::probe :seon.log/message "line two"})
          (let [content (.readFileSync fs path "utf-8")
                lines   (->> (str/split content #"\n")
                             (remove str/blank?))
                parsed  (map edn/read-string lines)]
            (is (= 2 (count lines)))
            (is (every? map? parsed) "every line is a readable edn map")
            (is (= "line one" (:seon.log/message (first parsed))))
            (is (= :error    (:seon.log/level   (second parsed))))
            (is (every? #(instance? js/Date (:seon.log/at %)) parsed)
                ":seon.log/at deserializes as js/Date via #inst"))))
      (finally (cleanup! path)))))

(deftest file-rotates-at-cap
  (let [path (tmp-file)]
    (try
      (with-log-file* path
        (fn []
          ;; Tiny cap so a handful of entries triggers rotation.
          (log/configure! {:seon.log/file-cap 512 :seon.log/keep 3})
          (let [big (apply str (repeat 200 "X"))]
            (dotimes [i 8]
              (log/info! {:seon.log/source ::probe
                          :seon.log/message (str "msg " i " " big)}))
            (is (.existsSync fs path)
                "current event file exists")
            (is (or (.existsSync fs (str path ".1"))
                    (.existsSync fs (str path ".2")))
                "at least one rotation occurred"))))
      (reset-config!)
      (finally (cleanup! path)))))

(deftest tail-survives-simulated-pod-restart
  ;; A pod restart in V0 means the JVM/Node process dies and a new one
  ;; starts against the same on-disk log file. There is no in-memory
  ;; ring buffer to lose — `tail` reads the file each call, so the new
  ;; process sees every entry the old one wrote. We simulate by
  ;; reconfiguring `:seon.log/file` to the SAME path (no ring buffer
  ;; means there's nothing else to reset).
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          (log/info! {:seon.log/source ::pre :seon.log/message "pre-restart"})))
      ;; "Restart" — entirely new configure! call.
      (with-log-file* path
        (fn []
          (log/info! {:seon.log/source ::post :seon.log/message "post-restart"})
          (let [out (log/tail {:seon.log/n 10})]
            (is (= 2 (count out)) "both pre- and post-restart entries visible")
            (is (= "post-restart" (:seon.log/message (first out))))
            (is (= "pre-restart"  (:seon.log/message (last out)))))))
      (finally (cleanup! path)))))

(deftest log-bang-never-throws
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          ;; Even with weird data shapes, log! should soft-fail.
          (is (some? (log/info! {:seon.log/source ::probe
                                 :seon.log/message "ok"
                                 :seon.log/data    {:nested {:circular "fine"}}})))))
      (finally (cleanup! path)))))

(deftest tail-empty-when-no-file
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (with-log-file* path
        (fn []
          ;; Don't write anything — file doesn't exist yet.
          (is (= [] (log/tail {:seon.log/n 50})))))
      (finally (cleanup! path)))))

(deftest configure-bang-updates-file-key
  ;; Direct contract test for the new behavior — :seon.log/file lives
  ;; in !config, not in a dynvar. This is the regression guard for the
  ;; Phase 1.5 -> Phase 2 migration.
  (let [saved @log/!config
        path  "logs/configure-bang-probe.log"]
    (try
      (log/configure! {:seon.log/file path})
      (is (= path (:seon.log/file @log/!config))
          ":seon.log/file is read from !config")
      (is (contains? @log/!config :seon.log/file-cap)
          "configure! preserves other keys")
      (finally (reset! log/!config saved)))))

(deftest unconfigured-process-has-no-file-sink
  ;; The regression guard for the 2026-07-20 error-channel flood: every
  ;; bin/test-cljs process shared the pod's repo-relative default file, so
  ;; provider-failure fixtures ("500 boom", CUDA OOM) and receipt-test
  ;; "tx FAILED: program row rejected" lines drowned the LIVE
  ;; logs/pod-events.log at :error level. A process that never claimed a
  ;; file must be console-only: no write target, an empty tail, no throw.
  (let [saved @log/!config]
    (try
      (reset! log/!config (dissoc saved :seon.log/file))
      (is (not (contains? @log/!config :seon.log/file))
          "pristine config carries NO file — optional = absent")
      (is (some? (log/error! {:seon.log/source ::probe
                              :seon.log/message "console-only fixture noise"}))
          "logging without a configured file still succeeds (console sink)")
      (is (= [] (log/tail {:seon.log/n 5}))
          "tail over no configured file is empty, never a throw")
      (finally (reset! log/!config saved)))))
