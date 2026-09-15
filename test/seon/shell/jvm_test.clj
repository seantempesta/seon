(ns seon.shell.jvm-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [my.shell :as shell]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.fs :as filesystem]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.shell.jvm]
            [seon.test-support :as support])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.lang ProcessHandle]
           [java.util HexFormat]))

(defn- handler
  []
  (deref (ns-resolve 'seon.shell.jvm 'run)))

(defn- shell-private
  [function-name]
  (deref (ns-resolve 'seon.shell.jvm function-name)))

(defn- temp-tree
  []
  (let [base (io/file "tmp/my-shell-test" (str (random-uuid)))]
    (.mkdirs base)
    (.toAbsolutePath (.toPath base))))

(defn- with-temp-tree
  [f]
  (let [root (temp-tree)]
    (try
      (f root)
      (finally
        (filesystem/delete-recursively! (str root) (str root))))))

(defn- effective
  [root overrides]
  (merge
   (config/defaults)
   {:seon.config.fs/working-root (str root)
    :seon.config.fs/roots [(str root)]
    :seon.config.shell/time-limit-ms 5000
    :seon.config.shell/termination-grace-ms 250
    :seon.config.shell/inline-output-bytes 4096
    :seon.config.shell/preview-bytes 1024
    :seon.config.shell/stdin-max-bytes (* 16 1024 1024)}
   overrides))

(defn- with-file-database
  [root f]
  (support/with-published-file-database
   root :my-shell-test
   (fn [connection]
     (let [configured (config/apply!
                       {:seon.db/connection connection
                        :seon.boot/cluster-name "shell-test"
                        :seon.config/manifest
                        {:seon.config.eval.result/blob-threshold 4096}})]
       (when (:seon.error/kind configured)
         (throw (ex-info "Shell fixture configuration was refused." configured))))
     (f connection))))

(defn- with-handler
  [effective-map f]
  (with-file-database
    (:seon.config.fs/working-root effective-map)
    (fn [connection]
      (binding [effect/*request-context*
                {:seon.db/connection connection}]
        (f connection
           (fn [request effective-config]
             (let [result ((handler) request effective-config)]
               (if-let [staged-writes (:seon.blob/staged-writes result)]
                 (blob/with-publication!
                  connection staged-writes
                  #(dissoc result :seon.blob/staged-writes))
                 result)))
           effective-map)))))

(defn- sha-256
  [octets]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest digest ^bytes octets))))

(defn- descriptor-octets
  [connection descriptor]
  (cond
    (contains? descriptor :my.shell.output/text)
    (.getBytes ^String (:my.shell.output/text descriptor)
               StandardCharsets/UTF_8)

    (contains? descriptor :my.shell.output/octet-values)
    (byte-array (map unchecked-byte
                     (:my.shell.output/octet-values descriptor)))

    :else
    (let [size (:my.shell.output/bytes descriptor)
          digest (:my.shell.output/blob descriptor)]
      (blob/read-chunk connection digest 0 size))))

(defn- unsigned-octets
  [octets]
  (mapv #(bit-and 0xff %) ^bytes octets))

(defn- nul-fields
  [octets]
  (loop [index 0
         start 0
         fields []]
    (if (= index (alength ^bytes octets))
      (cond-> fields
        (< start index)
        (conj (String. ^bytes octets start (- index start)
                       StandardCharsets/UTF_8)))
      (if (zero? (aget ^bytes octets index))
        (recur (inc index) (inc index)
               (conj fields
                     (String. ^bytes octets start (- index start)
                              StandardCharsets/UTF_8)))
        (recur (inc index) start fields)))))

(defn- split-environment-field
  [field]
  (let [separator (.indexOf ^String field "=")]
    [(subs field 0 separator) (subs field (inc separator))]))

(deftest capture-task-completion-is-bounded-and-interrupts-only-that-task
  (let [started (promise)
        interrupted (promise)
        task
        ((shell-private 'virtual-task)
         "seon-shell-never-completes-"
         (fn []
           (deliver started true)
           (try
             @(promise)
             (catch InterruptedException failure
               (deliver interrupted true)
               (throw failure)))))
        _ (is (= true (deref started 1000 ::not-started)))
        result
        ((shell-private 'task-result)
         task
         {:seon.config.eval/time-limit-ms 20}
         ::stdout-capture)]
    (is (= :seon.await/backstop-fired (:seon.error/kind result)))
    (is (= ::stdout-capture
           (get-in result [:seon.error/data
                           :seon.error/diagnostic-member])))
    (is (= true (deref interrupted 1000 ::not-interrupted)))))

(deftest binary-output-is-byte-exact-on-both-sides-of-the-inline-ceiling
  (with-temp-tree
    (fn [root]
      (with-handler
        (effective root {:seon.config.shell/inline-output-bytes 8})
        (fn [connection run effective-map]
          (doseq [octets [[0 255 65 66 67 68 69]
                          [0 255 65 66 67 68 69 70 71]]]
            (let [hex-octets (apply str (map #(format "%02x" %) octets))
                  source
                  (str "import os; os.write(1, bytes.fromhex('"
                       hex-octets "'))")
                  result
                  (run {:my.shell/argv ["/opt/homebrew/bin/python3"
                                        "-c" source]
                        :my.shell/cwd "."}
                       effective-map)
                  stdout (:my.shell/stdout result)
                  actual (descriptor-octets connection stdout)]
              (is (= octets (unsigned-octets actual)))
              (is (= (count octets) (:my.shell.output/bytes stdout)))
              (is (= (sha-256 (byte-array octets))
                     (:my.shell.output/digest stdout)))
              (if (<= (count octets) 8)
                (do
                  (is (contains? stdout :my.shell.output/octet-values))
                  (is (not (contains? stdout :my.shell.output/blob))))
                (do
                  (is (contains? stdout :my.shell.output/blob))
                  (is (false? (:my.shell.output/preview-complete?
                               stdout))))))))))))

(deftest child-environment-is-complete-and-declared-overrides-win
  (with-temp-tree
    (fn [root]
      (let [override-path "/seon/declared/override"]
        (with-handler
          (effective root {:seon.config.shell/path override-path})
          (fn [connection run effective-map]
            (let [result
                  (run {:my.shell/argv ["/usr/bin/env" "-0"]
                        :my.shell/cwd "."}
                       effective-map)
                  child-environment
                  (into {}
                        (map split-environment-field)
                        (nul-fields
                         (descriptor-octets
                          connection (:my.shell/stdout result))))
                  expected (assoc (into {} (System/getenv))
                                  "PATH" override-path)]
              (is (= expected child-environment)))))))))

(deftest stdout-and-stderr-drain-concurrently-without-loss
  (with-temp-tree
    (fn [root]
      (with-handler
        (effective root {})
        (fn [connection run effective-map]
          (let [size (* 2 1024 1024)
                source
                (str "import os\n"
                     "chunk = bytes(range(256)) * 256\n"
                     "remaining = " size "\n"
                     "while remaining:\n"
                     " n = min(len(chunk), remaining)\n"
                     " os.write(1, chunk[:n])\n"
                     " os.write(2, chunk[:n])\n"
                     " remaining -= n\n")
                result
                (run {:my.shell/argv ["/opt/homebrew/bin/python3" "-c" source]
                      :my.shell/cwd "."}
                     effective-map)
                expected
                (byte-array
                 (take size (cycle (range 256))))]
            (is (= 0 (:my.shell/exit result)))
            (doseq [descriptor [(:my.shell/stdout result)
                                (:my.shell/stderr result)]]
              (is (= size (:my.shell.output/bytes descriptor)))
              (is (= (sha-256 expected)
                     (:my.shell.output/digest descriptor)))
              (is (= (seq expected)
                     (seq (descriptor-octets connection descriptor)))))))))))

(deftest argv-stdin-and-nonzero-exit-remain-process-evidence
  (with-temp-tree
    (fn [root]
      (with-handler
        (effective root {})
        (fn [connection run effective-map]
          (let [arguments ["space value" "quote\"value" "*.clj"
                           "$HOME" "semi;colon"]
                source
                (str "import os,sys\n"
                     "data = sys.stdin.buffer.read()\n"
                     "os.write(1, b'\\0'.join(x.encode() for x in sys.argv[1:]))\n"
                     "os.write(2, data)\n"
                     "raise SystemExit(23)\n")
                result
                (run {:my.shell/argv
                      (into ["/opt/homebrew/bin/python3" "-c" source]
                            arguments)
                      :my.shell/cwd "."
                      :my.shell/stdin {:my.shell/stdin-bytes [0 255 10]}}
                     effective-map)]
            (is (= 23 (:my.shell/exit result)))
            (is (= arguments
                   (nul-fields
                    (descriptor-octets connection (:my.shell/stdout result)))))
            (is (= [0 255 10]
                   (unsigned-octets
                    (descriptor-octets connection
                                       (:my.shell/stderr result)))))
            (is (nil? (:seon.error/kind result)))))))))

(deftest cwd-outside-roots-refuses-before-process-start
  (with-temp-tree
    (fn [root]
      (with-handler
        (effective root {})
        (fn [_connection run effective-map]
          (let [marker (.resolve ^Path root "must-not-exist")
                result
                (run {:my.shell/argv ["/usr/bin/touch" (str marker)]
                      :my.shell/cwd (str (.getParent ^Path root))}
                     effective-map)]
            (is (= :my.shell/cwd-refused (:seon.error/kind result)))
            (is (not (Files/exists marker
                                   (make-array java.nio.file.LinkOption 0))))))))))

(deftest time-limit-reaps-the-process-tree-and-marks-the-effect-interrupted
  (with-temp-tree
    (fn [root]
      (with-file-database
        root
        (fn [connection]
          (let [written (db/transact!
                         connection
                         [{:seon.agent/id "shell-author"}
                          {:seon.turn/id "shell-time-limit"
                           :seon.turn/agent [:seon.agent/id "shell-author"]
                           :seon.turn/opened-tx "datomic.tx"}])]
            (is (:db-after written) (pr-str written)))
          (let [effective-map
                (effective root
                           {:seon.config.shell/time-limit-ms 750
                            :seon.config.shell/termination-grace-ms 100})
                configured (config/apply! {:seon.db/connection connection
                                           :seon.boot/cluster-name "shell-test"
                                           :seon.config/manifest effective-map})
                _ (is (not (:seon.error/kind configured)) (pr-str configured))
                context
                {:seon.db/connection connection
                 :seon.env/environment (support/environment "shell-test" connection)
                 :seon.agent/id "shell-author"
                 :seon.turn/id "shell-time-limit"
                 :seon.cluster.eval/ordinal 0
                 :seon.boot/cluster-name "shell-test"
                 :seon.sci.admit/caps (config/result-caps effective-map)
                 :seon.config/on-core-error :record
                 :seon.effect/counter (atom -1)}
                result
                (binding [effect/*request-context* context]
                    (shell/run!
                     {:my.shell/argv
                      ["/bin/sh" "-c"
                      (str "trap '' TERM; "
                            "/bin/sh -c \"trap '' TERM; "
                            "while :; do /bin/sleep 1; done\" & "
                            "child=$!; printf '%s' \"$child\" > child.pid; "
                            "while :; do /bin/sleep 1; done")]
                      :my.shell/cwd "."}))
                stored-effect
                (db/q '[:find (pull ?effect [*]) . :where
                         [?turn :seon.turn/id "shell-time-limit"]
                         [?effect :seon.effect/run ?turn]] @connection)
                child-pid
                (parse-long
                 (slurp (.toFile (.resolve ^Path root "child.pid"))))
                child-handle (ProcessHandle/of child-pid)]
            (testing "timeout is a flat capability error with output evidence"
              (is (= :my.shell/time-limit (:seon.error/kind result)))
              (is (map? (get-in result
                                [:seon.error/data :my.shell/stdout])))
              (is (map? (get-in result
                                [:seon.error/data :my.shell/stderr]))))
            (testing "the effect is interrupted rather than settled"
              (is (inst? (:seon.effect/interrupted-at stored-effect)))
              (is (nil? (:seon.effect/result-edn stored-effect)))
              (is (nil? (:seon.effect/settled-at stored-effect))))
            (testing "the descendant that ignored polite termination is gone"
              (is (or (.isEmpty child-handle)
                      (not (.isAlive ^ProcessHandle (.get child-handle))))))))))))

(deftest an-evaluations-deadline-reaps-the-child-it-admitted
  ;; The class: a child process outliving the evaluation that admitted it.
  ;; Two limits govern a foreground child — the shell's and the evaluation's —
  ;; and only the shell's was observed. When an eval time limit fired while
  ;; the handler was parked on the child, SCI's interrupt had no interpreted
  ;; function entrance to reach, so `sleep 300` was still running 29 s after
  ;; its 4 s limit and its effect stayed permanently pending. The wait is
  ;; now bounded by whichever limit ends first, so no arm of the handler can
  ;; return while its child is alive, and the `:interrupted` disposition is
  ;; what stamps the effect (proven by the sibling shell-limit test, which
  ;; drives the same disposition through effect/request!).
  (with-temp-tree
    (fn [root]
      (with-file-database
        root
        (fn [connection]
          (let [effective-map
                (effective root
                           ;; the shell's own limit is far away: only the
                           ;; evaluation's deadline can end this child
                           {:seon.config.shell/time-limit-ms 600000
                            :seon.config.shell/termination-grace-ms 100})
                armed (kernel/arm (sci.eval/build-base-ctx) 750)
                started (System/nanoTime)
                result
                (try
                  (binding [effect/*request-context*
                            {:seon.db/connection connection}]
                    ((handler)
                     {:my.shell/argv
                      ["/bin/sh" "-c"
                       (str "printf '%s' \"$$\" > child.pid; "
                            "while :; do /bin/sleep 1; done")]
                      :my.shell/cwd "."}
                     effective-map))
                  (finally ((:seon.sci.kernel/stop! armed))))
                elapsed-ms (quot (- (System/nanoTime) started) 1000000)
                child-pid
                (parse-long
                 (slurp (.toFile (.resolve ^Path root "child.pid"))))
                child-handle (ProcessHandle/of child-pid)]
            (testing "the child is cut by the evaluation deadline, not the shell's"
              (is (= :my.shell/time-limit (:seon.error/kind result)))
              (is (str/includes? (:seon.error/message result) "evaluation")
                  "the refusal names which limit ended the child")
              (is (< elapsed-ms 30000)
                  "the handler returned at the evaluation deadline"))
            (testing "the terminal disposition is what settles the effect"
              (is (= :interrupted (:seon.effect/disposition result))))
            (testing "no orphan survives the run"
              (is (or (.isEmpty child-handle)
                      (not (.isAlive ^ProcessHandle (.get child-handle))))))))))))
