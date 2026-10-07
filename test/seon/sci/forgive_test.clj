(ns seon.sci.forgive-test
  "The forgiving read of a model reply: what it reads, and that its cost
  follows the reply's length."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [are deftest is]]
            [seon.sci.forgive :as forgive]
            [seon.turn :as turn]))

(deftest recorded-replies-read-into-the-same-segments
  ;; Twelve recorded model replies: the linear read keeps every form, comment
  ;; and unread rest the repairing read produced.
  (doseq [{reply :text expected :segments} (edn/read-string (slurp "test/seon/sci/forgive_replies.edn"))]
    (is (= expected (forgive/segments reply)))))

(deftest ^{:seon/tasks #{'seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote}}
  a-delimiter-repair-is-judged-on-the-forms-own-lines
  (are [reply sources] (= sources (mapv :seon.batch/source (:seon.batch/segments (forgive/segments reply))))
    ;; dispatch forms read structurally, so their form is repaired
    "(meta #'x\n(+ 1 2)"                       ["(meta #'x)" "(+ 1 2)"]
    "(map #(inc %) [1 2]\n(+ 1 2)"             ["(map #(inc %) [1 2])" "(+ 1 2)"]
    "(let [a 1]\n  @a\n(+ 1 2)"                ["(let [a 1]\n  @a)" "(+ 1 2)"]
    ;; prose after the form's lines no longer refuses its repair
    "(defn f [x]\n  (inc x)\n\nNote: done."     ["(defn f [x]\n  (inc x))"]
    ;; a balanced form continued at column 0 is not repaired
    "(let [a 1]\n(+ a 1))"                     ["(let [a 1]\n(+ a 1))"]
    ;; a string's column-0 line is inside the form's lines
    "(defn f\n  \"doc\nmore\" [x]\n  (inc x)"  ["(defn f\n  \"doc\nmore\" [x]\n  (inc x))"]
    ;; a stray closer is dropped only before a form
    "(+ 1 2))\n(+ 3 4)"                       ["(+ 1 2)" "(+ 3 4)"]))

(defn- allocated-reading
  "Bytes this thread allocates segmenting `reply` (-1 on a virtual thread,
  for which HotSpot keeps no count)."
  {:malli/schema [:=> [:cat :string] :int]}
  [reply]
  (let [threads ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        before (.getCurrentThreadAllocatedBytes threads)]
    (forgive/segments reply)
    (if (neg? before) -1 (- (.getCurrentThreadAllocatedBytes threads) before))))

(deftest ^{:seon/tasks #{'seon.sci.forgive/a-prose-repair-replays-the-whole-reply
                         'seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote}}
  a-reply-reads-in-allocation-linear-in-its-length
  ;; Prose words, prompt markers and dispatch forms each repaired the whole
  ;; rest of the reply: four times the reply allocated about twenty times more.
  (let [unit (str "Note: the value is read here.\n```clojure\nuser=> (meta #'x)\n(map #(inc %) [1 2])\n"
                  "(re-find #\"a\" @a)\n```\n`reply-map` joins entries; turns left: 6 of 12\n")
        reply (apply str (repeat 40 unit))
        longer (apply str (repeat 160 unit))
        _ (allocated-reading reply)
        once (allocated-reading reply)]
    (is (pos? once) "the allocation counter measures the read")
    (is (< (allocated-reading longer) (* 6 once))
        "four times the reply allocates at most six times as much")))

(deftest a-reply-over-the-source-bound-is-one-refused-source
  ;; The bound applies before segmentation, so the reader's own refusal answers it.
  (let [reply "(+ 1 2)\n(+ 3 4)"]
    (is (= [reply] (mapv :seon.cluster.eval/source (turn/planned-sources reply 'user 10))))
    (is (= [reply] (mapv :seon.cluster.eval/source (turn/code-sources reply 'user 10))))
    (is (= ["(+ 1 2)" "(+ 3 4)"] (mapv :seon.cluster.eval/source (turn/planned-sources reply 'user 100))))))
