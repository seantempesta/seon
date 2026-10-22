(ns seon.lib-test
  "The native probes (native/probe.c through seon.lib): what they answer and what they cost."
  (:require [seon.profile :as profile] [clojure.test :refer [deftest is testing]] [clojure.java.shell] [seon.lib :as lib]
            [konserve.jolt.lmdb-ffi :as lmdb]))

(defn- probe-ms
  "The median time of 9 calls of lib/mp-probe, ms."
  []
  (let [ts (sort (for [_ (range 9)] (let [t0 (profile/now-ns)] (lib/mp-probe) (profile/ms-since t0))))]
    (nth ts 4)))

(deftest the-memory-probe-costs-what-the-process-holds-not-what-it-maps
  ;; lib/mp-probe asked the kernel for each region's extended info, which walks every page of
  ;; the region: an agent maps the store's LMDB file twice at :seon.config.store/lmdb-map-bytes
  ;; (64 GiB), so one probe took ~1.25 s and 100 agents probing at their start serialized on
  ;; the file's object (100 ready in 18 s, 640 ms before the map grew; session spawn-regression,
  ;; 2026-10-22). Its cost must not grow with what the process maps: a probe with a 64 GiB map
  ;; open costs about what it costs with a 1 GiB map. Two LMDB envs on disk of this test's own,
  ;; the last resort: what is proved is a mapping's size; removed at the end
  (let [dir (str (System/getProperty "user.dir") "/target/lib-test-" (random-uuid))
        _ (doseq [d ["-small" "-large"]] (.mkdirs (java.io.File. (str dir d))))
        small (lmdb/open-env (str dir "-small") {:map-size (* 1 1024 1024 1024)})
        t-small (probe-ms)
        large (lmdb/open-env (str dir "-large") {:map-size (* 64 1024 1024 1024)})
        t-large (probe-ms)]
    (try
      (is (< t-large (+ 1.0 (* 2 t-small))) (str "probe " t-small " ms with 1 GiB mapped, " t-large " ms with 64 GiB more"))
      (testing "and it answers this process's own memory, in MB"
        (let [p (lib/mp-probe) mb (lib/real-mb p)]
          (is (< 1.0 mb (* 1.05 (:phys-fp p 0))) (str "real " mb " MB, footprint " (:phys-fp p)))))
      (finally (lmdb/close-env small) (lmdb/close-env large)
               (clojure.java.shell/sh "rm" "-rf" (str dir "-small") (str dir "-large"))))))

(deftest a-fork-beside-a-running-thread-is-refused-and-says-how-many-run
  ;; lib/fork! is the one spawning skeleton: a second active Chez thread breaks a forked
  ;; child (E11/E13), so it forks nothing and answers its refused branch with the count
  (let [stop (promise)
        spin (doto (Thread. #(loop [] (when-not (realized? stop) (recur)))) (.start))
        r (lib/fork! {:child #(lib/c-exit 3) :parent (fn [pid] [:forked pid]) :refused (fn [n] [:refused n])})]
    (deliver stop true)
    (.join spin)
    (is (= :refused (first r)) (str "answered " r))
    (is (<= 2 (second r)) (str "active threads " (second r)))))
