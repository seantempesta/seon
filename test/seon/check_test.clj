(ns seon.check-test
  "bin/check's store (owner, 2026-10-21: one store, many branches, as in production): the run
  seeds one store (seon.check/seed); every step's root opens it on a main of its own, a
  branch of the seed (seon.db/open!, as seon.system/start! does), names its agents' branches
  under that main, and a recovery step reopens the same store on the same main."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [clojure.core.async :as a] [seon.check] [test-runner] [seon.doc] [seon.problems] [seon.fx] [seon.source] [clojure.string] [clojure.string :as str] [seon.lib] [seon.contract :as contract] [clojure.set]))

(defn- notes [db] (set (d/q '[:find [?t ...] :where [_ :note/text ?t]] db)))

(deftest ^{:seon.test/estimate {:ticks 100000}} steps-name-their-agents-branches-under-their-own-main
  ;; Estimate: five pure keyword joins and one filter over a three-element vector: ~100k ticks. (The on-disk LMDB opens, reopen
  ;; and delete-main! pins of the old test died with bin/check; seon.db-test proves open! and delete-main!.)
  (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :agent-1)))
  (is (= :smoke-1/agent-1 (pdb/on-main :smoke-1 :smoke-1/agent-1)))
  (is (= :smoke-1 (pdb/on-main :smoke-1 :db)))
  (is (not= (pdb/on-main :smoke-1 :agent-1) (pdb/on-main :drain-1 :agent-1)))
  (is (= :agent-1 (pdb/on-main :db :agent-1)))
  (is (= [:agent-1] (pdb/of-main :smoke-1 (set [(pdb/on-main :smoke-1 :agent-1) (pdb/on-main :drain-1 :agent-1)])))))

(deftest ^{:seon.test/estimate {:ticks 200000}} the-probe-says-what-it-took
  ;; the system check awaits the probe's
  ;; own event for its third message, not a window
  (let [ev (a/chan 4)
        st (#'seon.check/probe-proc {:tag "one" :events ev})
        [st o1] (#'seon.check/probe-proc st :in 1)
        [_ o2] (#'seon.check/probe-proc st :in 2)]
    (is (= {:events ev} (:clojure.core.async.flow/out-ports st)))
    (is (= [[{:type :probe :tag "one" :got 1}] [{:type :probe :tag "one" :got 2}]]
           (map #(mapv (fn [e] (dissoc e :at-ms)) (:events %)) [o1 o2])))))

(defn- catch-db
  "The test store's seed with n live forms in a src namespace, each defining one var and carrying a catch the law page
  does not name (each an :unnamed-site), as seon.problems-test's catch-forms; and the n-th var's name."
  [db0 n]
  (let [ns-e (:db/id (:var/ns (d/entity db0 (:e (first (d/datoms db0 :aevt :var/name))))))]
    [(d/db-with db0 (into [[:db/add ns-e :ns/file "src/app/m.clj"]]
                          (mapcat (fn [i] [{:db/id (- (inc (* 2 i))) :form/id (str "check-form-" i) :form/ns ns-e :form/order i :form/src "(defn f [])"
                                            :form/catches #{"{:class Exception, :answers :expr}"} :form/defines [(- (* 2 i))]}
                                           {:db/id (- (* 2 i)) :var/name (symbol "app.check" (str "v" i)) :var/ns ns-e :var/form (- (inc (* 2 i)))}]))
                          (range 1 (inc n))))
     (symbol "app.check" (str "v" n))]))

;; docs/references.md#task-references: a task's citation of nothing is refused at its write (owner, 2026-11-09); the health step loads
;; the tree's tasks through that one checked write, so a task citing a key no program holds is health's typed refusal, never a lookup
;; Datahike throws (batch 15: a plan citing :db/contentId in one backtick threw out of health before any row)
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-task-citing-nothing-is-the-health-steps-typed-refusal
  (let [dir (str (System/getProperty "java.io.tmpdir") "/health-cite-" (random-uuid))
        tasks (str dir "/" (#'test-runner/tasks-dir))
        _ (clojure.java.shell/sh "mkdir" "-p" tasks (str dir "/docs"))
        _ (spit (str tasks "/zz.edn") (pr-str [{:seon.task/name "t" :seon.task/plan "Read `:no.such/key` first." :seon.task/kind :work :seon.task/proofs #{}}]))
        r (#'test-runner/with-wiki (ts/test-db) dir)]
    (clojure.java.shell/sh "rm" "-rf" dir)
    (is (seon.contract/error? r) "an answer, not a throw")
    (is (= {:seon.error/type :seon.db/no-target :target [:schema/key :no.such/key] :by "zz/t"} (select-keys r [:seon.error/type :target :by])) (pr-str r))
    (is (str/includes? (str (:seon.error/message r)) "two backticks") "its message says what to write for a name that is no citation")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-problem-the-base-had-is-not-the-branchs
  (let [[base] (catch-db (ts/test-db) 1)
        h (test-runner/branch-health base base nil)]
    (is (pos? (get-in (seon.problems/health base) [:classes :unnamed-site :n])) "the base has an open problem")
    (is (= [] (:caused h)) "the branch changed nothing, so it caused none")
    (is (every? true? (vals (:checks h))) "and its health is green")))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-problem-the-branch-made-fails-it
  (let [[base] (catch-db (ts/test-db) 1)
        [head v] (catch-db base 2)
        h (test-runner/branch-health base head nil)]
    (is (= [:unnamed-site] (mapv :class (:caused h))))
    (is (str/includes? (:message (first (:caused h))) (str v)) "naming what the branch made")
    (is (false? (get-in h [:checks :no-unnamed-site])) "its health is red on the class it made")
    (testing "the classes asked for are the ones judged (bin/laws catch asks for :unnamed-site): one the branch did not make is green"
      (is (= {:no-unnamed-site true} (:checks (test-runner/branch-health base base [:unnamed-site])))))
    (testing "a class no verdict judges (an attention class never fails a branch) is refused, never a green"
      (is (thrown? Exception (test-runner/branch-health base head [:stale-section]))))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-class-the-health-step-does-not-read-is-refused
  (let [base (ts/test-db)]
    ;; the running system's state is no branch's content: no verdict of caused-split judges it
    (doseq [c [:stale-pin :stale-binary :unsynced :problem-unlinked]]
      (is (thrown? Exception (test-runner/branch-health base base [c])) (str (name c) " is no verdict of caused-split")))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} the-health-steps-checks-are-the-classes-caused-reads
  (let [[base] (catch-db (ts/test-db) 1)
        attention? (:seon.problems/attention (contract/properties :seon.problems/class))]
    (is (= (into #{} (comp (remove attention?) (map #(keyword (str "no-" (name %))))) seon.problems/caused-classes)
           (set (keys (:checks (test-runner/branch-health base base nil))))))
    (let [[head] (catch-db base 2)]
      (is (every? seon.problems/caused-classes (map :class (:rows (seon.problems/caused-split base head))))
          "every class caused-split answers is one the health step judges"))))

(deftest ^{:seon.test/estimate {:ticks 1000000}} a-feed-that-ends-unsettled-ends-the-wait-that-names-it
  ;; docs/checks.md#modes (nothing hangs: docs/errors.md#nothing-crashes-and-nothing-hangs; a live producer that owes a mark is
  ;; seon.web/a-tabs-first-keyframe-settles-on-its-own): seon.check/idle waits on a feed only its reader thread fills. A stream that
  ;; sends bytes and then ends (or throws on read) without a settled mark answers the wait false, so
  ;; the check's :tab-settled is red, at once; before, the wait had no end. A stream that does settle
  ;; answers true, one that ends before any byte fails the first-bytes wait
  (let [feed-of (fn [^String text] (#'seon.check/watch-feed! (java.io.ByteArrayInputStream. (.getBytes text "UTF-8"))))
        await-feed #'seon.check/await-feed
        unsettled (feed-of "event: datastar-patch-elements\ndata: x\n")
        settled (feed-of "event: datastar-patch-elements\n: settled\n")
        empty-feed (feed-of "")
        throws (#'seon.check/watch-feed! (proxy [java.io.InputStream] [] (read ([] 0) ([b] (throw (java.io.IOException. "reset"))) ([b o l] (throw (java.io.IOException. "reset"))))))]
    (is (true? (await-feed unsettled #(pos? (:bytes %)))) "its first bytes arrived")
    (is (false? (await-feed unsettled :settled)) "ended without a settled mark: the wait answers false, not never")
    (is (= "eof" (:ended @unsettled)) "and says why it ended")
    (is (true? (await-feed settled :settled)))
    (is (false? (await-feed empty-feed #(pos? (:bytes %)))) "ended before its first bytes")
    (is (false? (await-feed throws :settled)))
    (is (= "read threw: reset" (:ended @throws)))))

;; docs/processes.md#a-stop-reaps-every-child: one rule decides what a stop left running, for drain, lifecycle and orphan
;; (seon.check/ran-nothing-more?). Batch 46's lifecycle left {:pid 87561 :ppid 1 :stat "?NE" :command "(seon)"}: an orphan
;; exiting, which a rule counting only zombies read as running
(deftest ^{:seon.test/estimate {:ticks 200000}} a-process-that-can-run-nothing-more-is-not-left-by-a-stop
  (let [self 4242]
    (is (seon.check/ran-nothing-more? {:pid 87561 :ppid 1 :stat "?NE" :command "(seon)"} self) "an orphan exiting")
    (is (seon.check/ran-nothing-more? {:pid 9 :ppid 1 :stat "Z" :command "(seon)"} self) "an orphan zombie")
    (is (not (seon.check/ran-nothing-more? {:pid 9 :ppid 1 :stat "S" :command "seon"} self)) "an orphan still running stays")
    (is (not (seon.check/ran-nothing-more? {:pid 9 :ppid self :stat "Z" :command "(seon)"} self)) "a child the stop failed to reap stays")
    (is (not (seon.check/ran-nothing-more? nil self)) "a pid ps did not find is judged by alive?, not here")))

;; docs/processes.md#a-stop-reaps-every-child: batch 58b's lifecycle left {:pid 74564} with no ps row, under load: kill 0
;; found it, then ps listed it no more, so it had exited between the two reads, and the check counted it left
(deftest ^{:seon.test/estimate {:ticks 200000}} a-pid-gone-between-two-reads-is-not-left-by-a-stop
  (let [self 4242]
    (is (not (seon.check/left? nil false self)) "no ps row and kill 0 finds it no more: it exited, not left")
    (is (seon.check/left? nil true self) "no ps row but still there when asked again: left")
    (is (seon.check/left? {:pid 9 :ppid 1 :stat "S"} true self) "a row still running: left")
    (is (not (seon.check/left? {:pid 9 :ppid 1 :stat "Z"} true self)) "a row that can run nothing more: not left")))


;; docs/server.md#working-a-task-from-a-worktree: bin/mcp relays each message on its own connection to root, and answers each as it
;; comes, so one call waiting (a prove parked on a gate) blocks no other: measured 2026-11-14, one held call kept a session's every
;; other tool call waiting 17 min while root answered others in 3 ms. Here a stand-in root holds call 1 open and answers call 2
(deftest ^{:seon.test/execs ["bin/mcp"] :seon.test/estimate {:ticks 3000000}} a-relay-answers-a-quick-call-while-a-slow-one-waits
  ;; estimate: one relay process started (~1M ticks of ours to start it and read its pipes) and three socket accepts
  (with-open [ss (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))]
    (let [p (.start (ProcessBuilder. ^java.util.List [(str (seon.config/checkout) "/bin/mcp") (str (.getLocalPort ss))]))
          in (.getOutputStream p)
          line! (fn [^java.io.OutputStream os s] (.write os (.getBytes (str s "\n") "UTF-8")) (.flush os))
          out (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))
          answer! (fn [^java.net.Socket s line]
                    (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s))))
                    (line! (.getOutputStream s) line)
                    (.close s))]
      (try
        (line! in "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}")
        (let [slow (.accept ss)]
          (line! in "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")
          (answer! (.accept ss) "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}")
          (is (str/includes? (.readLine out) "\"id\":2") "the quick call is answered while the slow one waits")
          (answer! slow "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}")
          (is (str/includes? (.readLine out) "\"id\":1") "and the slow one when root answers it, a whole line"))
        (finally (.close in) (.waitFor p))))))

;; docs/server.md#mcp: a draining root answers a new call at once with :seon.system/restarting and the candidate's port (task
;; seon.server/a-call-is-answered-across-a-restart, switch-over S4), before any op runs, so the relay sends that same line once to the
;; candidate and relays its answer: the session never sees the restart. Here stand-in roots: the old answers the restarting error, the
;; new the call. Red before: the relay passed the old root's error to the session
;; estimate: one relay process (~1M ticks of ours to start it and read its pipes), three socket accepts
(deftest ^{:seon.test/execs ["bin/mcp"] :seon.test/estimate {:ticks 3000000}} a-relay-call-in-flight-across-a-restart-is-answered
  ;; task seon.server/a-relayed-call-in-flight-across-a-restart-is-answered (flow's report: an eval in flight across a restart, the
  ;; client gave up at 1800 s): the relay waits exactly as long as a root holds the call's connection, so a root that stops mid-call
  ;; (its socket closed, no answer) is an error the session reads at once, and so is a candidate that closes after a draining root
  ;; passed the call on. Fake roots: plain server sockets that read the line and close, so nothing waits on a clock
  (with-open [dying (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))
              old (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))
              new (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))]
    (let [relay (fn [port] (.start (ProcessBuilder. ^java.util.List [(str (seon.config/checkout) "/bin/mcp") (str port)])))
          line! (fn [^java.io.OutputStream os s] (.write os (.getBytes (str s "\n") "UTF-8")) (.flush os))
          read-line! (fn [^java.net.Socket s] (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))))
          call "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"}"]
      (testing "a root that stops mid-call: the session reads an error naming it, never waits"
        (let [p (relay (.getLocalPort dying)) out (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))]
          (try
            (line! (.getOutputStream p) call)
            (let [s (.accept dying)] (read-line! s) (.close s))
            (let [answered (.readLine out)]
              (is (str/includes? (str answered) "\"id\":7") (str "the call is answered: " answered))
              (is (str/includes? (str answered) "stopped mid-request") (str "naming the stop: " answered)))
            (finally (.close (.getOutputStream p)) (.waitFor p)))))
      (testing "a candidate that closes after a draining root passed the call on: the session reads the restart"
        (let [p (relay (.getLocalPort old)) out (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))]
          (try
            (line! (.getOutputStream p) call)
            (let [s (.accept old)]
              (read-line! s)
              (line! (.getOutputStream s) (str "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32000,\"message\":\"root is restarting\","
                                               "\"data\":{\"type\":\"seon.system/restarting\",\"next-port\":" (.getLocalPort new) "}}}"))
              (.close s))
            (let [c (.accept new)] (read-line! c) (.close c))
            (let [answered (.readLine out)]
              (is (str/includes? (str answered) "seon.system/restarting") (str "the restart is the answer: " answered)))
            (finally (.close (.getOutputStream p)) (.waitFor p))))))))

(deftest ^{:seon.test/execs ["bin/mcp"] :seon.test/estimate {:ticks 3000000}} a-relay-follows-a-draining-root-to-its-candidate
  ;; estimate: one relay process started (~1M ticks of ours to start it and read its pipes) and two socket accepts
  (with-open [old (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))
              new (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName "127.0.0.1"))]
    (let [p (.start (ProcessBuilder. ^java.util.List [(str (seon.config/checkout) "/bin/mcp") (str (.getLocalPort old))]))
          in (.getOutputStream p)
          line! (fn [^java.io.OutputStream os s] (.write os (.getBytes (str s "\n") "UTF-8")) (.flush os))
          out (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))
          answer! (fn [^java.net.Socket s line]
                    (let [got (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s))))]
                      (line! (.getOutputStream s) line)
                      (.close s)
                      got))]
      (try
        (line! in "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"}")
        ;; the candidate's accept on its own thread, and the session's answer read here: a relay that passes the restart on answers at
        ;; once and this is red, and closing the candidate ends its accept, so nothing waits on a clock or hangs
        (let [first-got (answer! (.accept old) (str "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32000,\"message\":\"root is restarting\","
                                                     "\"data\":{\"type\":\"seon.system/restarting\",\"next-port\":" (.getLocalPort new) "}}}"))
              again (future (answer! (.accept new) "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[]}}"))
              answered (.readLine out)]
          (is (str/includes? answered "\"result\"") (str "the session reads the candidate's answer, not the restart: " answered))
          (when (str/includes? answered "\"result\"")
            (is (str/includes? @again "\"id\":7") (str "the candidate is sent the same call: " @again))
            (is (= (str/replace first-got #"\"seon/client\":\"[^\"]*\"," "") (str/replace @again #"\"seon/client\":\"[^\"]*\"," "")) "the line as it was sent")))
        (finally (.close new) (.close in) (.waitFor p))))))

