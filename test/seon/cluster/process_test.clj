(ns seon.cluster.process-test
  "The subprocess termination owner: TERM, grace, verified force, awaited exit.
  Every process here is started by the test itself."
  (:require [babashka.process :as bp]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.process :as process])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- start-tree
  "Start `script` under sh and return its record and identities once
  `processes` members of its tree are visible (bounded at 2 s)."
  [script processes]
  (let [record (bp/process ["/bin/sh" "-c" script] {:shutdown bp/destroy-tree})
        root (.toHandle ^Process (:proc record))
        deadline (+ (System/nanoTime) 2000000000)]
    (loop []
      (let [identities (process/subprocess-tree-identities root)]
        (if (or (<= processes (count identities)) (> (System/nanoTime) deadline))
          {::record record ::identities identities}
          (do (Thread/sleep 20) (recur)))))))

(defn- survivors
  [identities]
  (filterv process/process-identity-alive? identities))

(deftest a-grandchild-ignoring-term-dies-when-its-root-exits-within-grace
  (let [{record ::record identities ::identities}
        (start-tree "(trap '' TERM; exec /bin/sleep 30) & /bin/sleep 30" 3)
        started (System/nanoTime)
        reaped? (process/terminate-subprocess! record identities 500 2000)
        elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (is (= 3 (count identities)) (pr-str identities))
    (testing "the root obeyed TERM, the grandchild needed the force after grace"
      (is (= 143 (.exitValue ^Process (:proc record))))
      (is (<= 500 elapsed-ms 2500) (str elapsed-ms " ms")))
    (is (true? reaped?))
    (is (= [] (survivors identities)))))

(deftest a-well-behaved-tree-exits-on-term-without-force
  (let [directory (Files/createTempDirectory "seon-process-test" (make-array FileAttribute 0))
        marker (.toFile (.resolve directory "term"))
        {record ::record identities ::identities}
        (start-tree (str "cd '" directory "' && /bin/sh -c "
                         "'trap \"echo term > term; exit 0\" TERM; "
                         "while :; do /bin/sleep 0.1; done' & wait")
                    3)
        started (System/nanoTime)
        reaped? (process/terminate-subprocess! record identities 5000 2000)
        elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (try
      (is (true? reaped?))
      (is (= [] (survivors identities)))
      (testing "every member exited on TERM inside the grace"
        (is (= 143 (.exitValue ^Process (:proc record))) "the root died of TERM, not KILL")
        (is (= "term\n" (when (.exists marker) (slurp marker))) "the child ran its TERM trap")
        (is (< elapsed-ms 2000) (str elapsed-ms " ms")))
      (finally
        (.delete marker)
        (Files/deleteIfExists directory)))))
