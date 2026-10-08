(ns seon.printer-elision-test
  "The value printer's general cut rules, through the MCP entrance: what a cut
  reply shows, and that its elision form, evaluated through the same entrance,
  reads exactly the rest with `clojure.core`."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- reply-text
  "The reply text of one `eval_clj` of `code` as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [code agent-id running]
  (support/mcp-text
   (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                             "arguments" {"code" code "agent" agent-id "cluster" running}})))))

(defn- reply
  "The reply map of one `eval_clj` of `code` as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [code agent-id running]
  (support/reply (reply-text code agent-id running)))

(defn- core-only?
  "True when `form` names only `clojure.core` functions and result handles."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [form]
  (every? #(or (= "result" (namespace %)) (some? (ns-resolve 'clojure.core %)))
          (filter symbol? (tree-seq coll? seq form))))

(defn- continued?
  "True when the elision form `form` evaluates, through the entrance, to what
  `expected` (a `clojure.core` form over the same handle) reads."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value :string :string] :boolean]}
  [form expected agent-id running]
  (true? (:seon.repl/value (reply (pr-str (list '= form expected)) agent-id running))))

(deftest a-child-cap-follows-the-budget
  ;; 400 small maps fill the budget instead of stopping at a member count.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [{handle :seon.repl/result shown :seon.repl/value} (reply "(vec (repeat 400 {:a 1 :b \"x\"}))" agent-id running)
           members (filterv map? shown)
           form (last shown)]
       (is (< 100 (count members) 400) "the budget, not a member count, decides how many show")
       (is (= (list 'drop (count members) handle) form))
       (is (continued? form (list 'drop (count members) (list 'vec (list 'repeat 400 {:a 1 :b "x"}))) agent-id running))))))

(deftest a-cut-set-or-map-continues-where-it-stopped
  ;; A map or set past 64 members shows its first members in `seq` order, and
  ;; its cut continues with `drop` on the handle.
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [code ["(zipmap (range 3000) (range))" "(set (range 3000))"]]
       (let [{handle :seon.repl/result shown :seon.repl/value} (reply code agent-id running)
             form (if (map? shown) (some (fn [[k v]] (when (symbol? k) v)) shown) (some #(when (seq? %) %) shown))
             kept (count (if (map? shown) (remove (comp symbol? key) shown) (remove #(or (symbol? %) (seq? %)) shown)))]
         (is (= (list 'drop kept handle) form))
         (is (continued? (list 'concat (list 'take kept handle) form) (list 'seq handle) agent-id running))
         (is (continued? (list 'into (if (map? shown) {} #{}) (list 'take kept handle))
                         (list 'quote (into (empty shown) (if (map? shown) (remove (comp symbol? key) shown) (remove #(or (symbol? %) (seq? %)) shown))))
                         agent-id running)
             "the shown members are the first of the value's seq order"))))))

(deftest elision-forms-name-only-clojure-core
  ;; A cut under a set member or a non-keyword key reads its path with
  ;; `nth`, `seq`, `key` and `val`, never a Seon helper.
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[code expected] [["#{(vec (range 5000))}" '(drop N (first (seq H)))]
                              ["{\"k\" (vec (range 5000))}" '(drop N (get H "k"))]]]
       (let [{handle :seon.repl/result shown :seon.repl/value} (reply code agent-id running)
             form (first (filter seq? (tree-seq coll? seq shown)))
             kept (second form)]
         (is (core-only? form) "the elision form names clojure.core only")
         (is (continued? form (walk/postwalk-replace {'N kept 'H handle} expected) agent-id running)))))))

(deftest the-out-elision-form-reads-the-live-output
  ;; Printed output, a large value and findings share the reply's one budget;
  ;; each cut continues from its own live source.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [printing "(dotimes [i 3000] (println \"line\" i \"of the printed output\"))"
           text (reply-text (str "(do " printing " (vec (range 2000)))") agent-id running)
           [_ out-handle out-offset] (re-find #"\(subs (result/o[0-9a-f]+) (\d+)\)" text)
           [_ value-offset value-handle] (re-find #"\(drop (\d+) (result/e[0-9a-f]+)\)" text)]
       (is (<= (tokens/estimate text) 2000) "one budget covers value, output and findings")
       (is (re-find #":findings" text))
       (is (and out-handle value-handle) "both parts are cut, each with its own source")
       (when (and out-handle value-handle)
         (is (continued? (list 'subs (symbol out-handle) (parse-long out-offset))
                         (list 'subs (list 'with-out-str (read-string printing)) (parse-long out-offset))
                         agent-id running))
         (is (continued? (list 'drop (parse-long value-offset) (symbol value-handle))
                         (list 'drop (parse-long value-offset) (list 'vec (list 'range 2000)))
                         agent-id running))))
     (let [small (reply "(do (println \"hi\") 1)" agent-id running)]
       (is (= ["hi\n" 1] [(:seon.repl/out small) (:seon.repl/value small)]) "a small reply is whole")))))

(defn- shown-members
  "How many of `(vec (range 3000))`'s members a reply shows."
  {:malli/schema [:=> [:cat :map] :int]}
  [reply]
  (count (filter int? (:seon.repl/value reply))))

(defn- override-finding
  "The reply's budget-override finding, or nil."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [reply]
  (some #(when (= :budget-override (:seon.repl/finding %)) %) (:seon.repl/findings reply)))

(deftest a-budget-override-widens-one-form
  ;; :seon.print/budget metadata widens one value; a session set! of
  ;; seon.print/*budget* widens every later reply until it is set back.
  (support/with-running-agent
   (fn [running agent-id _]
     (let [plain (reply "(vec (range 3000))" agent-id running)
           widened (reply "(with-meta (vec (range 3000)) {:seon.print/budget 4000})" agent-id running)
           _ (reply "(set! seon.print/*budget* 4000)" agent-id running)
           session (reply "(vec (range 3000))" agent-id running)
           _ (reply "(set! seon.print/*budget* nil)" agent-id running)
           restored (reply "(vec (range 3000))" agent-id running)]
       (is (< (shown-members plain) (shown-members widened)) "metadata widens the value")
       (is (= 4000 (:seon.eval/budget-override (override-finding widened))) "the finding names its token count")
       (is (nil? (override-finding plain)))
       (is (= (shown-members widened) (shown-members session)) "the session's set! widens the next form")
       (is (= (shown-members plain) (shown-members restored)) "set! nil restores the profile's budget")))))

(deftest an-override-past-the-ceiling-is-capped-with-a-finding
  (support/with-running-agent
   (fn [running agent-id _]
     (let [_ (reply "(set! seon.print/*budget* 100000000)" agent-id running)
           capped (reply "[1 2 3]" agent-id running)]
       (reply "(set! seon.print/*budget* nil)" agent-id running)
       (is (= [1 2 3] (:seon.repl/value capped)))
       (is (= (:seon.config.ai/prompt-token-budget config/defaults)
              (:seon.eval/budget-override (override-finding capped)))
           "the model's prompt window caps the override")))))
