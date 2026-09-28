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
  `processes` members of its tree are visible (bounded at 2 s).
  Output goes to /dev/null, never to the JDK's pipes: the JDK closes those when
  the root exits (`ProcessImpl$ProcessPipeInputStream/processExited`), so a
  member whose TERM handler still writes (sh reports its killed `sleep` on
  stderr) would die of SIGPIPE, a fault of the fixture, not of the owner."
  [script processes]
  (let [record (bp/process ["/bin/sh" "-c" script]
                           {:shutdown bp/destroy-tree :out :discard :err :discard})
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
        left (process/terminate-subprocess! record identities 500 2000)
        elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (is (= 3 (count identities)))
    (testing "the root obeyed TERM, the grandchild needed the force after grace"
      (is (= 143 (.exitValue ^Process (:proc record))))
      (is (<= 500 elapsed-ms 2500)))
    (is (= [] left))
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
        left (process/terminate-subprocess! record identities 5000 2000)
        elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (try
      (is (= [] left))
      (is (= [] (survivors identities)))
      (testing "every member exited on TERM inside the grace"
        (is (= 143 (.exitValue ^Process (:proc record))) "the root died of TERM, not KILL")
        (is (= "term\n" (when (.exists marker) (slurp marker))) "the child ran its TERM trap")
        (is (< elapsed-ms 2000)))
      (finally
        (.delete marker)
        (Files/deleteIfExists directory)))))

(defn zombie
  "A real process no signal can end: a child exited but unreaped by its living
  perl parent, which the caller must destroy. Returns the parent and the
  zombie's identity. On macOS (2026-09-24) the zombie stays alive through
  SIGKILL until its parent exits."
  []
  (let [parent (.start (ProcessBuilder.
                        ["/usr/bin/perl" "-e" "my $c=fork(); if($c==0){exit 0} print \"$c\\n\"; STDOUT->flush(); sleep 30"]))
        pid (Long/parseLong (.readLine (java.io.BufferedReader.
                                        (java.io.InputStreamReader. (.getInputStream parent)))))
        handle (.get (java.lang.ProcessHandle/of pid))]
    {::parent parent
     ::identity (first (process/subprocess-tree-identities handle))}))

(deftest a-survivor-of-the-cleanup-bound-is-returned-never-reported-reaped
  (let [{parent ::parent survivor ::identity} (zombie)
        {record ::record identities ::identities} (start-tree "/bin/sleep 30" 1)]
    (try
      (is (= [survivor] (process/terminate-subprocess! record (conj identities survivor) 100 100)))
      (is (process/process-identity-alive? survivor) "the owner ended nothing it did not observe")
      (is (= [] (survivors identities)))
      (finally (.destroyForcibly ^Process parent)))))

(deftest an-exited-or-unknown-generation-never-matches-a-process
  (let [record (bp/process ["/bin/sleep" "30"])
        child ^Process (:proc record)
        [member :as identities] (process/subprocess-tree-identities (.toHandle child))]
    (try
      (testing "a live member carries its generation and matches only it"
        (is (= 1 (count identities)))
        (is (inst? (:seon.boot/start-instant member)))
        (is (= (:seon.boot/start-instant member)
               (process/process-start-instant (:seon.boot/pid member))))
        (is (some? (process/matching-process-handle member)))
        (is (nil? (process/matching-process-handle
                   (update member :seon.boot/start-instant
                           #(java.util.Date. (- (inst-ms %) 1000)))))))
      (testing "a record without its generation refuses or misses, never matches"
        (is (not (process/process-handle?
                  (try (process/matching-process-handle
                        (assoc member :seon.boot/start-instant nil))
                       (catch Exception _ :refused))))))
      (finally
        (when (process/process-identity-alive? member) (.destroy child))
        (.waitFor child)))
    (testing "an exited process is nothing: no start instant, no identity"
      (is (nil? (process/process-start-instant (:seon.boot/pid member))))
      (is (= [] (process/subprocess-tree-identities (.toHandle child))))
      (is (false? (process/live? member))))))
