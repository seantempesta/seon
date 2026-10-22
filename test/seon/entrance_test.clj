(ns seon.entrance-test
  "Root's entrance as an outside agent reaches it (seon.entrance, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.entrance-test"
  (:require [clojure.test :refer [deftest is]] [seon.agent-test :as at]))

(deftest a-root-eval-replies-like-an-agent-eval
  ;; task seon.entrance/root-evals-through-the-one-repl: an eval as root goes through the
  ;; agents' REPL path, so its reply items are an agent's: the one printer, a result
  ;; handle, the error's message, *1 kept between requests
  (let [[a-ret a-err r-ret r-err r-star]
        (at/in-agent ["{:a 1}" "(/ 1 0)"
                      {:op :eval :agent "root" :code "{:a 1}"} {:op :eval :agent "root" :code "(/ 1 0)"}
                      {:op :eval :agent "root" :code "(count (:agents (c/state sys)))"}])]
    (is (= (dissoc a-ret :handle :recorded) (dissoc r-ret :handle)) "a value replies alike")
    (is (keyword? (:handle r-ret)) "root's value has its result handle, as an agent's does")
    (is (= (select-keys a-err [:tag :form :printed]) (select-keys r-err [:tag :form :printed])) "an error replies alike")
    (is (= "1" (:printed r-star)) "`sys` is this root: one agent")))

(deftest a-door-defect-answers-that-request-alone
  ;; task seon.entrance/a-door-defect-answers-that-request-alone: a request whose verb throws
  ;; (attach! refuses a task id that is not a string: its contract) is answered the typed
  ;; :seon.entrance/defect naming the line it took, and the door answers the next request
  (let [[defect ok] (at/in-agent [{:op :attach :task 5} {:op :eval :agent "root" :code "(+ 1 2)"}])]
    (is (= :seon.entrance/defect (:seon.error/type defect)))
    (is (= (pr-str {:op :attach :task 5}) (:line defect)))
    (is (= "3" (:printed ok)) "the door goes on")))
