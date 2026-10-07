(ns seon.sci.forgive-test
  "The reader stage: what it reads from a model reply and from code, and that
  its cost follows the text's length."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [are deftest is]]
            [seon.sci.forgive :as forgive]
            [seon.turn :as turn]))

(deftest recorded-replies-read-into-the-same-segments
  ;; Recorded model replies: every form, failed read and comment the line rules find.
  (doseq [{reply :text expected :segments} (edn/read-string (slurp "test/seon/sci/forgive_replies.edn"))]
    (is (= expected (forgive/segments reply)))))

(deftest ^{:seon/tasks #{'seon.sci.forgive/discard-consumes-prose-and-fence-boundaries}}
  the-reader-reads-a-reply-by-its-line-rules
  (are [mode text sources] (= sources (mapv :seon.batch/source (:seon.batch/segments (forgive/segments text mode))))
    ;; a failed collection is one source; reading resumes after its closer
    :reply "```clojure\n[1 bad: (+ 2 3)]\n(+ 4 5)\n```"          ["[1 bad: (+ 2 3)]" "(+ 4 5)"]
    :reply "```clojure\n(defn f [x] (let [y #1] (inc y)))\n(+ 1 2)\n```" ["(defn f [x] (let [y #1] (inc y)))" "(+ 1 2)"]
    :reply "```clojure\n(+ 1 2))\n(+ 3 4)\n```"                   ["(+ 1 2)" ")" "(+ 3 4)"]
    :reply "```clj\n(foo]\n(+ 1 2)\n```"                          ["(foo]" "(+ 1 2)"]
    :reply "{:a 1 :b}\n(+ 1 2)"                                     ["{:a 1 :b}" "(+ 1 2)"]
    ;; no read crosses a fence line: an unclosed form is unfinished at its region's end
    :reply "```clj\n(str \"abc\n```\n(+ 1 2)"                     ["(str \"abc" "(+ 1 2)"]
    ;; a non-code fence is prose, its tagged lines included; a fence closes only on its match
    :reply "```text\n(+ 1 2)\n```clj\n(+ 9 9)\n```\n```clj\n(+ 3 4)\n```" ["(+ 3 4)"]
    :reply "````clojure\n```\n(+ 1 2)\n````\nDone."                ["```\n(+ 1 2)"]
    ;; outside fences a line's first character decides
    :reply "#inst \"2026-01-01\"\n#'x\n#:a{:b 1}\n@a\n^:m []\n'x"    ["#inst \"2026-01-01\"" "#'x" "#:a{:b 1}" "@a" "^:m []" "'x"]
    :reply "# Heading\n## h2\n`reply-map` joins\nDone.\n  (+ 3 4)\n(+ 1 2)" ["(+ 1 2)"]
    :reply "Done."                                                  []
    ;; every form on a line that starts one runs, as in a REPL
    :reply "(+ 1 2) (+ 3 4) ; sum\nDone."                          ["(+ 1 2)" "(+ 3 4)"]
    :reply "[] [] [999]"                                            ["[]" "[]" "[999]"]
    :reply "(+ 1 2) Done."                                          ["(+ 1 2)" "Done."]
    :reply "#1 priority is (launch!)"                               ["#1 priority" "is" "(launch!)"]
    ;; no-value constructs stay inside their region
    :reply "#_ (+ 1 2)\nDone.\n(+ 3 4)"                             ["(+ 3 4)"]
    :reply "```clj\n#_ (+ 1 2)\n```\nDone.\n(+ 3 4)"                ["(+ 3 4)"]
    :reply "```clj\n#_\n```\nDone.\n(+ 3 4)"                        ["(+ 3 4)"]
    :reply "#?(:cljs 1)\nDone.\n(+ 3 4)"                            ["(+ 3 4)"]
    ;; code is read as written, and a failed read continues there too
    :code  "(+ 1 1)\n(vector ::nope.alias/k)\n(+ 2 2)"              ["(+ 1 1)" "(vector ::nope.alias/k)" "(+ 2 2)"]
    :code  "Done.\n:k\n[1 bad: 2]\n42"                             ["Done." ":k" "[1 bad: 2]" "42"]))

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
  ;; Prose words, prompt markers and dispatch forms once each re-read the
  ;; whole rest of the reply: four times the reply allocated twenty times more.
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
