(ns seon.dev.hook-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.process :as process]
            [seon.operator :as operator]
            [seon.test-support :as support]))

;; Saves never publish (D1 O0): every route to default — its advertisement,
;; the prepl and a live-root evaluation — records instead of answering.
(def ^:private save-probe
  '(do
     (binding [*in* (java.io.StringReader. "{}") *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [calls (atom [])
           record (fn [route] (fn [& _] (swap! calls conj route) nil))
           config {:lint {:enabled false} :markdown-lint {:enabled false}
                   :docstring-lint {:enabled false} :shell-writes {:enabled false}
                   :schema-admission {:enabled false}
                   :contract-compile {:enabled false}}
           events [{:tool_name "Edit" :tool_input {:file_path "src/seon/id.clj"}}
                   {:tool_name "Write" :tool_input {:file_path "resources/seon/schemas/error.edn"}}
                   {:tool_name "Edit" :tool_input {:file_path "src/seon/cluster/boot.clj"}}
                   {:tool_name "Bash" :tool_input {:command "true"}}]
           replies (with-redefs [load-config (constantly config)
                                 operator/advertisement (record :advertisement)
                                 operator/prepl-value! (record :prepl)
                                 operator/live-root-value! (record :live-root)]
                     (mapv (fn [event]
                             (binding [*in* (java.io.StringReader.
                                             (json/generate-string
                                              (assoc event :hook_event_name "PostToolUse")))]
                               (with-out-str (-main))))
                           events))]
       (prn {:seon.probe/calls @calls :seon.probe/replies replies
             :seon.probe/publisher (resolve 'current-source-feedback)}))))

(deftest named-and-pathless-saves-never-publish
  (let [result (process/run-process!
                {:seon.operator.subprocess/argv ["bb" "-e" (pr-str save-probe)]
                 :seon.operator.subprocess/directory "."
                 :seon.operator.subprocess/deadline-ms
                 (* 1000 support/event-backstop-seconds)})]
    (is (zero? (:seon.operator.subprocess/exit result)))
    (when (zero? (:seon.operator.subprocess/exit result))
      (let [observed (edn/read-string (:seon.operator.subprocess/output result))]
        (is (= [] (:seon.probe/calls observed)))
        (is (nil? (:seon.probe/publisher observed)))
        (is (= 4 (count (:seon.probe/replies observed))))
        (is (not-any? #(str/includes? % "accepted") (:seon.probe/replies observed)))))))

(deftest output-does-not-extend-the-hook-terminal-bound
  (with-open [server (java.net.ServerSocket. 0)]
    (.setSoTimeout server 1000)
    (let [worker (future
                   (try
                   (with-open [socket (.accept server)
                               reader (java.io.PushbackReader. (io/reader socket))
                               writer (io/writer socket)]
                     (edn/read reader)
                     (try
                       (dotimes [_ 30]
                         (.write writer (str (pr-str {:tag :out :val "still working"}) "\n"))
                         (.flush writer)
                         (Thread/sleep 10))
                       (catch java.io.IOException _ nil)))
                   (catch java.io.IOException _ nil)))
          began (System/nanoTime)
          failure (try
                    (operator/prepl-value!
                     {:seon.boot/prepl-host "127.0.0.1"
                      :seon.boot/prepl-port (.getLocalPort server)} "(+ 1 1)" 1000 nil 50)
                    nil
                    (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
          elapsed (/ (- (System/nanoTime) began) 1e6)]
      @worker
      (is (map? failure))
      (is (str/includes? (:seon.error/message failure) "outcome unknown"))
      (is (< elapsed 500) "Output cannot renew the hook's total wait bound."))))

;; The hook is the lane's REPL entrance: the roots are recorded, never reached.
(def ^:private entrance-probe
  '(do
     (binding [*in* (java.io.StringReader. "{}") *out* (java.io.StringWriter.)]
       (load-file "bin/seon-hook"))
     (let [path (str (System/getProperty "user.dir") "/test/seon/dev/hook_entrance_fixture.clj")
           [unchanged changed] (top-level-definitions path)
           requests (atom [])
           config {:lint {:enabled false} :markdown-lint {:enabled false}
                   :docstring-lint {:enabled false} :shell-writes {:enabled false}
                   :schema-admission {:enabled false} :contract-compile {:enabled false}
                   :current-source {:root "." :cluster "default"}}
           event (json/generate-string {:hook_event_name "PostToolUse" :tool_name "Edit"
                                        :tool_input {:file_path path}})
           ns-form (:text (namespace-declaration (file-analysis path)))
           run (fn [endpoint branch-ns]
                 (reset! requests [])
                 (with-redefs [load-config (constantly config)
                               entrance-lane (constantly "probe-lane")
                               operator/advertisement (constantly endpoint)
                               operator/prepl-value!
                               (fn [_ form & _]
                                 (swap! requests conj
                                        (if (str/starts-with? form "(seon.mcp/call")
                                          (get-in (json/parse-string (second (read-string form)) true)
                                                  [:arguments])
                                          form))
                                 (if (= 1 (count @requests))
                                   {:vars {(:sym unchanged) {:source (:text unchanged) :host-bound? false}
                                           (:sym changed) {:source "(defn changed [x] x)" :host-bound? false}}
                                    :ns branch-ns}
                                   (json/generate-string {:content [{:type "text" :text "the reply"}]})))]
                   (binding [*in* (java.io.StringReader. event)]
                     (let [reply (with-out-str (-main))]
                       {:requests @requests :reply reply}))))]
       (prn {:seon.probe/live (run {:seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 1}
                                   {:requires #{} :aliases #{}})
             :seon.probe/new-ns (run {:seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 1} nil)
             :seon.probe/other-requires (run {:seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 1}
                                             {:requires #{'clojure.string} :aliases #{}})
             :seon.probe/ns-form ns-form
             :seon.probe/down (run nil nil)
             :seon.probe/changed-text (:text changed)
             :seon.probe/unchanged-text (:text unchanged)}))))

(deftest a-clojure-edit-evaluates-its-changed-defn-on-the-lane-branch
  (let [result (process/run-process!
                {:seon.operator.subprocess/argv ["bb" "-e" (pr-str entrance-probe)]
                 :seon.operator.subprocess/directory "."
                 :seon.operator.subprocess/deadline-ms
                 (* 1000 support/event-backstop-seconds)})]
    (is (zero? (:seon.operator.subprocess/exit result)))
    (when (zero? (:seon.operator.subprocess/exit result))
      (let [observed (edn/read-string (:seon.operator.subprocess/output result))
            live (:seon.probe/live observed)
            [state-form define-request test-request] (:requests live)
            define-code (:code define-request)
            ns-form (:seon.probe/ns-form observed)]
        (is (= 3 (count (:requests live))) "state read, definitions, reaching tests")
        (is (str/includes? state-form "my.program/source"))
        (is (str/includes? define-code (:seon.probe/changed-text observed))
            "the changed form is sent")
        (is (not (str/includes? define-code (:seon.probe/unchanged-text observed)))
            "the form the branch already holds is not sent")
        (is (= "seon.dev.hook-entrance-fixture" (:namespace define-request))
            "the namespace argument starts the run there")
        (is (not (str/includes? define-code "in-ns")) "no in-ns form is prepended")
        (is (not (str/includes? define-code ns-form)) "the ns form the branch holds is not sent")
        (is (str/includes? (:code test-request) "my.test/check"))
        (doseq [probe [:seon.probe/new-ns :seon.probe/other-requires]]
          (let [code (:code (second (:requests (get observed probe))))]
            (is (str/starts-with? code ns-form) (str probe ": the ns form is sent first"))
            (is (str/includes? code (:seon.probe/changed-text observed)))))
        (is (str/includes? (:reply live) "the reply"))
        (is (empty? (:requests (:seon.probe/down observed))) "no root is asked")
        (is (str/includes? (:reply (:seon.probe/down observed)) "default unavailable: lint only"))))))
