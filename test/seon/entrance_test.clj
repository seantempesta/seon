(ns seon.entrance-test
  "Root's entrance as an outside agent reaches it (seon.entrance, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.entrance-test"
  (:require [clojure.test :refer [deftest is]] [seon.agent-test :as at] [clojure.edn :as edn] [clojure.string :as str]
            [seon.contract :as contract] [seon.mcp :as mcp]))

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

(deftest an-entrance-refusal-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): the door refuses as root does, with the
  ;; one error map, never a second {:error "text"} shape
  (let [answers (at/in-agent [{:op :nope} {:op :context :agent "a9"} {:op :done :agent "a9"} {:op :attach :task "no/such"}])]
    (is (= 4 (count answers)))
    (doseq [a answers]
      (is (contract/error? a) (pr-str a))
      (is (nil? (:error a)) (pr-str a)))))

(deftest an-unreachable-entrance-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): the MCP tool's text when root's entrance
  ;; is not listening reads back as the one error map
  (let [port (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s))))
        a (edn/read-string (#'mcp/request! port {:op :agents}))]
    (is (contract/error? a) (pr-str a))
    (is (str/includes? (:seon.error/message a) (str port)))))
