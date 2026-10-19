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

(deftest help-teaches-replaying-a-proofs-fixture
  ;; Live run 9 (docs/research/agent-platform/2026-10-18/live-run-9.md): 10 of the fork-point chain's 11 wasted calls
  ;; tried to rebuild its proof's fixture at the REPL (a store from `seon.db/schema-database`, an unrequired
  ;; `seon.test-support/with-database`) and never ran it, so its cache's miss stayed unexplained until the cap.
  (let [text (slurp (io/resource "seon/help.md"))]
    (is (str/includes? text "(require 'seon.test-support)") "help names the require that loads the fixtures")
    (is (str/includes? text "(seon.test-support/with-database (fn [connection]") "help names the fixture a probe replays")
    (is (str/includes? text "(:seon.store/store (seon.test-support/execution-handle connection))")
        "help names where the fixture's store is")))

(deftest help-teaches-removing-what-a-branch-does-not-keep
  ;; Live run 10 (docs/research/agent-platform/2026-10-18/live-run-10.md): two chains sent
  ;; (my.program/ns-unmap! :a/registered-schema) three times, refused for its keyword, and two chains unmapped helpers
  ;; their own caller still called (six forms, each refused naming that caller): help named neither the schema's verb
  ;; nor the order.
  (let [text (slurp (io/resource "seon/help.md"))]
    (is (str/includes? text "(seon.schema/unregister! :the.owner/key)") "help names how a registered schema is removed")
    (is (str/includes? text "redefine its callers first") "help names the order ns-unmap! needs")
    ;; Live run 11 (docs/research/agent-platform/2026-10-18/live-run-11.md): the fork-point chain followed help's
    ;; fixture recipe, (in-ns 'user) then (require 'seon.test-support); its done step passed, and the final merge
    ;; refused :one-file because the user namespace changed and has no file.
    (is (not (str/includes? text "(in-ns 'user)")) "help never sends an agent to change the user namespace")
    (is (str/includes? text "(in-ns 'my.attempt-<your attempt>) then (require 'seon.test-support)")
        "help loads fixtures from the agent's own namespace, which never merges")))

(deftest help-and-every-step-template-describe-one-chain-branch-and-plan-updates
  ;; Task-system brief 2026-10-19 (docs/research/agent-platform/2026-10-19/task-system-brief.md, Broken 3): help
  ;; promised a merge onto main after every step, while the installed chain's steps share one branch and main gets one
  ;; merge after the last (TASK-CHAIN §2.5-2.6); and only Design's template told its agent to update the plan.
  (let [text (slurp (io/resource "seon/help.md"))
        instructions (fn [step] (:seon.task/instructions
                                 (first (read-string (slurp (io/resource (str "seon/tasks/seon.step." step ".edn")))))))]
    (is (not (str/includes? text "starts from main")) "no step starts from main mid-chain")
    (is (str/includes? text "Every step of your chain works on the same branch") "the steps share the chain's branch")
    (is (str/includes? text "merges onto main once") "main gets one merge, after the last step")
    (is (str/includes? text "Every step keeps the plan true") "help asks every step to correct the plan")
    ;; Live run 13: six of nine attempts followed help's (def task ...), and a Prove agent spent calls unmapping a
    ;; Design agent's def from the shared chain branch
    (is (not (str/includes? text "(def task")) "help binds the task symbols without a def on the chain branch")
    (doseq [step ["design" "prove" "implement" "merge"]]
      (is (str/includes? (instructions step) "my.task/update! (get-in (my.task/current) [:seon.context/task :seon.task/sym]) :seon.task/plan")
          (str "the " step " template names the plan update")))))
