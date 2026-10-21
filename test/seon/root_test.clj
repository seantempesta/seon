(ns seon.root-test
  "Root as a whole process, run as the built binary (target/seon, bin/build seon): a root
  boots its fork server, so it runs in a process of its own rather than in the test
  runner's, whose threads and GC sweepers are the runner's."
  (:require [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.edn :as edn]
            [clojure.string :as str] [seon.db :as pdb] [datahike.api :as d]))

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
  ;; Its store: target/runs/root-test-store, one kept store whose every boot is a new
  ;; branch, under runs/ so a worktree's clone of target/ leaves it out (bin/worktree: a
  ;; store's identity is its path, so a cloned one refuses every boot in the worktree).
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        {:keys [exit out err]} (sh/sh "env" "SEON_WEB_PORT=0" "./seon" "root"
                                      "--set" (str ":seon.config.source/root=" (pr-str checkout))
                                      "--set" ":seon.config.source/dirs=[\"src\" \"test\"]"
                                      "--set" (str ":seon.config.root/store-path=" (pr-str (str checkout "/target/runs/root-test-store")))
                                      :in "" :dir "target")]
    (is (= 0 exit) "the root boots with src and test imported")
    (is (str/includes? out "ROOT LISTENING") "and listens")
    (is (not (str/includes? (str out err) "Could not locate")) "every required library located")))

(deftest a-root-resumes-a-store-an-earlier-build-wrote
  ;; task seon.root/a-root-resumes-a-store-an-earlier-build-wrote: the store as builds of
  ;; 2026-10-21 before CONFIG landed left it (next/runs/store-full-20261021, read 17:35): its
  ;; schema lacks the attributes added since (the config's, the model manifest's, the source's), and its main's
  ;; root entity holds a quiescent fact and no boot (their boot fact was refused), which
  ;; seon.task/entity-value could not read. The built root resumes that main and answers a
  ;; root eval and {:op :agents}. A store on disk of this test's own, the last resort: what
  ;; is proved is a kept store another build wrote; removed at the end.
  (let [checkout (.getCanonicalPath (java.io.File. "."))
        path (str checkout "/target/root-test-resume-" (random-uuid))
        later? #(or (str/starts-with? (namespace (:db/ident %)) "seon.config") (= "seon.ai.model" (namespace (:db/ident %)))
                    (#{:ns/file :ns/tail :form/lead :form/source :seon.attempt/commit :seon.attempt/commit-error} (:db/ident %)))
        cfg (pdb/store-cfg :lmdb path)
        _ (d/create-database cfg)
        c (d/connect cfg)
        _ (d/transact c {:tx-data (vec (remove later? pdb/schema))})
        _ (doseq [tx (pdb/seed-tx 110)] (d/transact c {:tx-data tx}))
        _ (d/branch! c :db :old-run)
        c2 (d/connect (assoc cfg :branch :old-run))
        _ (d/transact c2 {:tx-data [{:seon.root/id :root :seon.root/quiescent "{:t 536870914, :stuck []}"}]})
        _ (pdb/sync! cfg)
        _ (do (d/release c2) (d/release c))
        port (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s))))
        ask (fn [m] (str "printf '%s\\n' " (pr-str (pr-str m)) " | nc -w 30 127.0.0.1 " port))
        ;; the root's stdin is a fifo held open until both answers are in; its listening line
        ;; (or its death) ends the wait, as bin/root's start does
        script (str "mkfifo '" path "-in'; ./seon root"
                    " --set ':seon.config.root/store-path=" (pr-str path) "'"
                    " --set ':seon.config.root/main=\"old-run\"'"
                    " --set ':seon.config.root/entrance-port=" port "' --set ':seon.config.web/port=0'"
                    " < '" path "-in' > '" path ".log' 2>&1 & R=$!; exec 3>'" path "-in';"
                    " until grep -q 'ROOT LISTENING' '" path ".log' || ! kill -0 $R 2>/dev/null; do sleep 0.1; done;"
                    (ask {:op :eval :agent "root" :code "(+ 1 2)"}) ";"
                    (ask {:op :agents}) ";"
                    (ask {:op :eval :agent "root" :code "(get-in (my.root/problems sys) [:classes :platform :n])"}) ";"
                    " exec 3>&-; wait $R; echo EXIT $?; cat '" path ".log'")
        {:keys [out]} (sh/sh "zsh" "-c" script :dir "target")
        [eval-line agents-line problems-line] (str/split-lines out)
        answer (fn [l] (try (edn/read-string {:default tagged-literal} l) (catch Exception _ nil)))]
    (try
      (is (str/includes? out "ROOT LISTENING") "the root boots on the earlier build's store")
      (is (= "3" (:printed (first (:items (answer eval-line))))) "a root eval answers")
      (is (map? (:tasks (answer agents-line))) "{:op :agents} answers its tasks")
      (is (= "0" (:printed (first (:items (answer problems-line))))) "and nothing it read was a defect")
      (is (str/includes? out "EXIT 0") "and it stops when its stdin closes")
      (finally (sh/sh "rm" "-rf" path (str path "-in") (str path ".log") (str path "-agents"))))))
