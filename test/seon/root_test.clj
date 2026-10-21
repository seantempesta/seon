(ns seon.root-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str]))

(deftest root-threads-are-what-root-declares
  ;; three start!/stop! cycles in one process: while running (three agents just
  ;; evaluated) and after each stop!, every thread is one seon.root/declared-threads
  ;; names, by name stem, the GC's sweepers are the declared number, and the OS counts
  ;; nothing else. A diff of {} is exact; anything else names what differs. No web
  ;; listener (SEON_WEB_PORT=0): its adapter's threads outlive stop! (task
  ;; web-server-threads-are-joined-at-stop).
  (let [{:keys [exit out err]} (sh/sh "env" "SEON_WEB_PORT=0" "./seon" "threads-declared" "3" :dir "target")
        res (edn/read-string (last (str/split-lines out)))]
    (is (= 0 exit) (str out err))
    (is (= 3 (count (:running res)) (count (:stopped res))))
    (is (= [{} {} {}] (:running res)))
    (is (= [{} {} {}] (:stopped res)))))

(deftest a-root-binary-requires-a-library-it-did-not-compile
  ;; task jolt.ns/a-built-binary-requires-a-stdlib-namespace-it-did-not-compile: a root
  ;; importing src and test runs each test file's ns form, and seon.print-test's requires
  ;; fipp.engine, which needs Jolt's stdlib clojure.core.rrb-vector; a binary finds only
  ;; what it compiled. The root boots, imports, and exits when its stdin (empty) closes.
  ;; Its store: target/root-test-store, one kept store whose every boot is a new branch.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        {:keys [exit out err]} (sh/sh "env" "SEON_WEB_PORT=0" "./seon" "root"
                                      "--set" (str ":seon.config.source/root=" (pr-str checkout))
                                      "--set" ":seon.config.source/dirs=[\"src\" \"test\"]"
                                      "--set" (str ":seon.config.root/store-path=" (pr-str (str checkout "/target/root-test-store")))
                                      :in "" :dir "target")]
    (is (= 0 exit) "the root boots with src and test imported")
    (is (str/includes? out "ROOT LISTENING") "and listens")
    (is (not (str/includes? (str out err) "Could not locate")) "every required library located")))
