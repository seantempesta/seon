(ns seon.log-test
  "Tests for file-only logging. Covers:
     - tail reads from the active log file (no ring buffer)
     - tail filters (level, agent, source)
     - NDJSON-EDN file write + readability
     - tail with N less than total
     - file rotation at file-cap
     - tail still works after a simulated pod restart (binding rebind
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
;; Per-test isolation — each test runs against a fresh tmp file via
;; `binding` on `seon.log/*log-file*` so we don't collide with the
;; live pod's log.
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

;; ============================================================
;; Tests
;; ============================================================

(deftest tail-reads-file-newest-first
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        (log/info! {:seon.log/source ::probe :seon.log/message "one"})
        (log/info! {:seon.log/source ::probe :seon.log/message "two"})
        (log/info! {:seon.log/source ::probe :seon.log/message "three"})
        (let [out (log/tail {:seon.log/n 10})]
          (is (= 3 (count out)) "all three written entries visible")
          (is (= "three" (:seon.log/message (first out))) "newest first")
          (is (= "one"   (:seon.log/message (last out))) "oldest last")))
      (finally (cleanup! path)))))

(deftest tail-with-n-less-than-total
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        (dotimes [i 10]
          (log/info! {:seon.log/source ::probe
                      :seon.log/message (str "msg " i)}))
        (let [out (log/tail {:seon.log/n 3})]
          (is (= 3 (count out)) "limited to N")
          (is (= "msg 9" (:seon.log/message (first out))) "newest first")
          (is (= "msg 7" (:seon.log/message (last out))) "third-newest last")))
      (finally (cleanup! path)))))

(deftest tail-filters-by-level
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        (log/info!  {:seon.log/source ::p :seon.log/message "i1"})
        (log/error! {:seon.log/source ::p :seon.log/message "e1"})
        (log/info!  {:seon.log/source ::p :seon.log/message "i2"})
        (log/error! {:seon.log/source ::p :seon.log/message "e2"})
        (is (= 2 (count (log/tail {:seon.log/level :error}))))
        (is (= 2 (count (log/tail {:seon.log/level :info}))))
        (is (= "e2" (-> (log/tail {:seon.log/level :error}) first :seon.log/message))))
      (finally (cleanup! path)))))

(deftest tail-filters-by-agent
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        (log/error! {:seon.log/source ::p :seon.log/message "m1"})
        (log/error! {:seon.log/source ::p :seon.log/agent "a1" :seon.log/message "m2"})
        (log/error! {:seon.log/source ::p :seon.log/agent "a2" :seon.log/message "m3"})
        (let [a1 (log/tail {:seon.log/agent "a1"})
              a2 (log/tail {:seon.log/agent "a2"})]
          (is (= 1 (count a1)))
          (is (= "m2" (:seon.log/message (first a1))))
          (is (= 1 (count a2)))
          (is (= "m3" (:seon.log/message (first a2))))))
      (finally (cleanup! path)))))

(deftest tail-filters-by-source
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        (log/info! {:seon.log/source ::alpha :seon.log/message "a"})
        (log/info! {:seon.log/source ::beta  :seon.log/message "b"})
        (let [out (log/tail {:seon.log/source ::beta})]
          (is (= 1 (count out)))
          (is (= "b" (:seon.log/message (first out))))))
      (finally (cleanup! path)))))

(deftest file-write-is-ndjson-edn
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
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
              ":seon.log/at deserializes as js/Date via #inst")))
      (finally (cleanup! path)))))

(deftest file-rotates-at-cap
  (let [path (tmp-file)]
    (try
      (binding [log/*log-file* path]
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
              "at least one rotation occurred")))
      (reset-config!)
      (finally (cleanup! path)))))

(deftest tail-survives-simulated-pod-restart
  ;; A pod restart in V0 means the JVM/Node process dies and a new one
  ;; starts against the same on-disk log file. There is no in-memory
  ;; ring buffer to lose — `tail` reads the file each call, so the new
  ;; process sees every entry the old one wrote. We simulate by
  ;; rebinding `*log-file*` to the SAME path under a fresh dynamic
  ;; scope (no ring buffer means there's nothing else to reset).
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        (log/info! {:seon.log/source ::pre  :seon.log/message "pre-restart"}))
      ;; "Restart" — entirely new binding scope.
      (binding [log/*log-file* path]
        (log/info! {:seon.log/source ::post :seon.log/message "post-restart"})
        (let [out (log/tail {:seon.log/n 10})]
          (is (= 2 (count out)) "both pre- and post-restart entries visible")
          (is (= "post-restart" (:seon.log/message (first out))))
          (is (= "pre-restart"  (:seon.log/message (last out))))))
      (finally (cleanup! path)))))

(deftest log-bang-never-throws
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        ;; Even with weird data shapes, log! should soft-fail.
        (is (some? (log/info! {:seon.log/source ::probe
                               :seon.log/message "ok"
                               :seon.log/data    {:nested {:circular "fine"}}}))))
      (finally (cleanup! path)))))

(deftest tail-empty-when-no-file
  (let [path (tmp-file)]
    (try
      (reset-config!)
      (binding [log/*log-file* path]
        ;; Don't write anything — file doesn't exist yet.
        (is (= [] (log/tail {:seon.log/n 50}))))
      (finally (cleanup! path)))))
