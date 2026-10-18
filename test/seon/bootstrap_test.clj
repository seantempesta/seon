(ns seon.bootstrap-test
  "Help is a Markdown document: its sections and the references they make."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cache :as cache]
            [seon.bootstrap :as bootstrap]
            [seon.test-support :as support]))

(deftest every-reference-in-help-resolves
  (support/with-database
   (fn [connection]
     (let [groups (:seon.help/groups (bootstrap/help-value @connection))
           references (mapcat :seon.help/references groups)]
       (is (seq references) "help makes references")
       (is (empty? (filter :seon.ref/problem-kind references)) "every reference resolves")))))

(deftest help-answers-one-section-by-its-anchor
  (support/with-database
   (fn [connection]
     (let [whole (:seon.help/groups (bootstrap/help-value @connection))
           [group] (:seon.help/groups (bootstrap/help-section @connection "write-what-you-learn"))
           expected (first (filter #(= "Write what you learn" (:seon.help/title %)) whole))]
       (is (= expected group) "the section's text and references, as `(help)` carries them")
       (is (= "write-what-you-learn" (:seon.help/anchor group)))
       (is (seq (:seon.help/references group)) "the section makes references")
       (is (= :heading (:seon.ref/section-missing (bootstrap/help-section @connection "no-such-section")))
           "an unknown anchor answers the nearest headings")))))

(defn- cited-doc
  "The first sentence help's references to `function` show at `database` (a summary's or a doc's), and the derivations that answer missed."
  [database function]
  (let [misses #(get-in (cache/status) [:seon.cache/kinds :seon.health/derived :seon.cache/misses] 0)
        before (misses)
        groups (:seon.help/groups (bootstrap/help-value database))]
    {:summary (->> groups (mapcat :seon.help/references) (filter #(= function (:seon.ref/name %))) (keep #(or (:summary %) (:doc %))) first)
     :misses (- (misses) before)}))

(deftest help-references-follow-the-program-they-cite
  (support/with-database
   (fn [connection]
     (cited-doc @connection 'seon.db/q)
     (is (zero? (:misses (cited-doc @connection 'seon.db/q))) "an unchanged program answers from the cache")
     (support/transacted!
      connection
      (support/replacement-tx @connection [:seon.fn/sym 'seon.db/q] "(defn q \"Changed by the test.\" [] 1)" {}))
     (is (str/includes? (str (:summary (cited-doc @connection 'seon.db/q))) "Changed by the test.")
         "a cited function's new definition shows in help's reference"))))

(deftest help-names-only-installed-keys
  (let [text (slurp (io/resource "seon/help.md"))
        bare (re-seq #"(?<![\w/.:-]):(?:value|error|findings|result|ms|alloc-mb)(?![\w/-])" text)]
    (is (empty? bare) "the reply's keys are named as :seon.repl/* keys")))

(deftest help-teaches-that-the-namespace-persists
  (let [text (slurp (io/resource "seon/help.md"))]
    (is (not (str/includes? text "2. Enter the namespace")) "entering a namespace is not a numbered step every definition repeats")
    (is (str/includes? text "stays in the namespace") "the REPL keeps the namespace the last form left")
    (is (str/includes? text "needs no in-ns") "a fully qualified name needs no in-ns")))

(deftest help-teaches-callers-let-probes-and-no-nilable-return
  ;; Live run 8 (docs/research/agent-platform/2026-10-18/live-run-8.md): 4 of 25 wasted calls searched text for a
  ;; subject's callers, 19 forms unmapped scratch defs (about 2 GB allocated), and 2 definitions were refused for a
  ;; bare nilable return the help never named.
  (let [text (slurp (io/resource "seon/help.md"))]
    (is (str/includes? text "(my.program/callers '") "help names the verb that reads a subject's callers")
    (is (str/includes? text "probe in a let") "help teaches probing with a let, which leaves nothing to unmap")
    (is (str/includes? text "returns no bare [:maybe") "help names the nilable-return refusal before the agent meets it")))
