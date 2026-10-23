(ns seon.main-test
  "A root holds no program it was not given (GUARDS #50): a fresh store, as seon.root/start!
  opens it by default, holds the schema and nothing else; root's warm-up needs no seed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [seon.root :as root] [seon.test-store :as ts] [test-runner :as runner]))

(defn- program-datoms [db]
  (vec (mapcat #(d/datoms db :aevt %) [:ns/name :form/id :var/name :task/name])))

(defn- fresh-store
  "A fresh store on disk of this test's own, the last resort: what is proved is what a
  fresh store holds when a root creates it. Answers [path opened]."
  []
  (let [path (str (System/getProperty "user.dir") "/target/main-test-" (random-uuid))]
    [path (pdb/open! :lmdb path 0 :db)]))

(deftest a-fresh-root-holds-no-program-it-was-not-given
  (let [[path {:keys [conn seeded?]}] (fresh-store)]
    (try
      (is seeded?)
      (is (= [] (pdb/seed-tx 0)) "no example program unless one is asked for")
      (is (= [] (program-datoms @conn)))
      (is (contains? (d/schema @conn) :var/name) "the schema is there")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest warm-needs-no-seed
  (let [[path {:keys [conn]}] (fresh-store)
        t (:max-tx @conn)]
    (try
      (is (= {:doc "warm" :task {:task/name :seon.warm/tidy :task/refs [{:var/name 'seon.warm/f}]}} (#'root/warm! conn))
          "its reads found its own definition and task")
      (is (= t (:max-tx @conn)) "warm-up commits nothing")
      (is (= [] (program-datoms @conn)))
      (is (nil? (find-ns 'seon.warm)) "its scratch namespace is gone")
      (finally (d/release conn) (clojure.java.shell/sh "rm" "-rf" path)))))

(deftest root-boots-without-test-fixtures
  ;; the compiler's facts of the checkout (src and test imported as program facts): the
  ;; release binary compiles what src requires, so no src namespace requires one whose file is
  ;; in test/ (the check driver, the e2e runner, the scripted model's fixtures: task
  ;; seon.main/drivers-leave-src); a scripted root loads its fixture from the checkout by name
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        file (into {} (d/q '[:find ?n ?f :where [?e :ns/name ?n] [?e :ns/file ?f]] db))
        in? (fn [dir n] (some-> (file n) (clojure.string/starts-with? dir)))
        reqs (d/q '[:find ?n ?r :where [?e :ns/name ?n] [?e :ns/form ?f] [?f :form/requires ?r]] db)]
    (is (in? "src/" 'seon.root) "the facts hold src's namespaces")
    (is (in? "test/" 'seon.fixtures) "and test's")
    (is (= #{} (set (for [[n r] reqs :when (and (in? "src/" n) (in? "test/" r))] [n r])))
        "no src namespace requires a test/ one")))

(deftest no-docstring-carries-a-date
  ;; task seon.main/docstrings-carry-no-dates-and-no-old-words: a docstring says what a var or
  ;; namespace is now; when a ruling was made and what a measurement read that day are git's and
  ;; DECISIONS'. Read from the compiler's facts of src (:var/doc, :ns/doc), so a new one is red
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        dated (fn [s] (re-find #"\b20\d\d-\d\d-\d\d\b" (or s "")))
        rows (concat (d/q '[:find ?n ?doc :where [?v :var/name ?n] [?v :var/doc ?doc] [?v :var/form ?f] [?f :form/ns ?ns] [?ns :ns/file ?file]
                            [(clojure.string/starts-with? ?file "src/")]] db)
                     (d/q '[:find ?n ?doc :where [?ns :ns/name ?n] [?ns :ns/doc ?doc] [?ns :ns/file ?file]
                            [(clojure.string/starts-with? ?file "src/")]] db))]
    (is (pos? (count rows)) "the facts hold src's docstrings")
    (is (= [] (sort (for [[n doc] rows :when (dated doc)] [n (dated doc)])))
        "no src docstring holds an ISO date")))

(deftest only-measure-reads-the-tick-counter
  ;; task seon.profile/a-call-is-measured-one-way: a call is measured one way, so no src form but
  ;; seon.profile/measure! reads the engine's tick counter (jolt.host/ticks-of or jolt.host/ticks):
  ;; a measurement built beside it is red here. Read from the compiler's facts
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        callers (d/q '[:find [?n ...] :in $ [?r ...] :where [?f :form/refs ?r] [?f :form/defines ?v] [?v :var/name ?n]
                       [?f :form/ns ?ns] [?ns :ns/file ?file] [(clojure.string/starts-with? ?file "src/")]]
                     db ['jolt.host/ticks-of 'jolt.host/ticks])]
    (is (= ['seon.profile/measure!] (vec callers)))))

(deftest a-runs-lifecycle-is-read-once
  ;; task seon.task/a-runs-lifecycle-is-read-once: a run's lifecycle sets are declared once, on
  ;; :seon.run/status's schema, and read once, by seon.task (finished, judged, failed), which every
  ;; view and judge requires; a second reader is red here. Each src form's recorded source read as
  ;; data (the compiler's facts: :form/src), never a text search
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        forms (d/q '[:find ?nsn ?src :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn] [?ns :ns/file ?file]
                     [(clojure.string/starts-with? ?file "src/")]] db)
        ;; read in the form's own namespace, so its alias keywords (::flow/in-ports) resolve, and its
        ;; reader conditionals as the compiler reads them (:clj); a call of either schema reader
        ;; (properties, enum-values) on :seon.run/status. Not covered: a call built by syntax-quote
        reads? (fn [nsn src] (require nsn)
                 (some #(and (seq? %) (symbol? (first %)) (#{"properties" "enum-values"} (name (first %)))
                             (= :seon.run/status (second %)))
                       (tree-seq coll? seq (binding [*read-eval* false *ns* (the-ns nsn)]
                                             (read-string {:read-cond :allow :features #{:clj}} src)))))]
    (is (= #{'seon.task} (into #{} (keep (fn [[nsn src]] (when (reads? nsn src) nsn))) forms)))))

(deftest an-errors-message-is-read-one-way
  ;; task seon.step/an-errors-message-is-read-one-way: an exception's message, or its class, is
  ;; seon.step/error-msg; no src form outside seon.step writes (or (ex-message e) (str (type e)))
  ;; again. src's forms read as data from the compiler's facts. Not covered: the same read written
  ;; another way ((.getMessage e), (str (class e)), the two in the other order)
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        forms (d/q '[:find ?nsn ?src :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn] [?ns :ns/file ?file]
                     [(clojure.string/starts-with? ?file "src/")]] db)
        read-in (fn [nsn src] (require nsn)
                  (binding [*read-eval* false *ns* (the-ns nsn)] (read-string {:read-cond :allow :features #{:clj}} src)))
        shape? (fn [x] (and (seq? x) (= 'or (first x))
                            (let [[a b] (rest x)]
                              (and (seq? a) (symbol? (first a)) (= "ex-message" (name (first a)))
                                   (seq? b) (symbol? (first b)) (= "str" (name (first b)))
                                   (seq? (second b)) (symbol? (first (second b))) (= "type" (name (first (second b))))))))
        writers (into (sorted-set) (for [[nsn src] forms :when (some shape? (tree-seq coll? seq (read-in nsn src)))] nsn))]
    (is (= #{'seon.step} writers))))
