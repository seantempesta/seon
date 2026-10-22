(ns seon.entrance-test
  "Root's entrance as an outside agent reaches it (seon.entrance, through the built root:
  seon.agent-test/in-agent). Run: bin/test seon.entrance-test"
  (:require [clojure.test :refer [deftest is]] [seon.agent-test :as at] [seon.contract :as contract] [seon.entrance]))

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

(deftest a-door-defect-answers-that-request-alone
  ;; task seon.entrance/a-door-defect-answers-that-request-alone: a request whose op throws
  ;; (attach! refuses a task id that is not a string: its contract) is answered the typed
  ;; :seon.entrance/defect naming the line it took, and the door answers the next request
  (let [[defect ok] (at/in-agent [{:op :attach :task 5} {:op :eval :agent "root" :code "(+ 1 2)"}])]
    (is (= :seon.entrance/defect (:seon.error/type defect)))
    (is (= (pr-str {:op :attach :task 5}) (:line defect)))
    (is (= "3" (:printed ok)) "the door goes on")))

(deftest the-door-speaks-mcp
  ;; task seon.entrance/the-door-answers-mcp-itself: the entrance answers MCP's JSON-RPC
  ;; itself (bin/mcp only relays lines): initialize, tools/list from the one op table,
  ;; nothing for a notification, an unknown tool named in its text. tools/call through a
  ;; real root is seon.check/mcp-proof (bin/check mcp), through the relay
  (let [ask #(seon.entrance/mcp-reply nil %)
        tools (get-in (ask {"jsonrpc" "2.0" "id" 2 "method" "tools/list"}) ["result" "tools"])]
    (is (= "2025-06-18" (get-in (ask {"id" 1 "method" "initialize" "params" {"protocolVersion" "2025-06-18"}}) ["result" "protocolVersion"]))
        "initialize answers the client's protocol version")
    (is (= (mapv #(str "seon_" (name %)) (keys seon.entrance/ops)) (mapv #(get % "name") tools))
        "every op is the tool seon_<op>, in the table's order")
    (is (= ["agent" "code"] (get-in (second tools) ["inputSchema" :required])) "an op's required arguments are its tool's")
    (is (nil? (ask {"jsonrpc" "2.0" "method" "notifications/initialized"})) "a notification is not answered")
    (is (= "no tool seon_nope" (get-in (ask {"id" 3 "method" "tools/call" "params" {"name" "seon_nope" "arguments" {}}}) ["result" "content" 0 "text"])))))
