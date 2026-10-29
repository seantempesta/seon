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
  (let [path (str (seon.config/checkout) "/target/main-test-" (random-uuid))]
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
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
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
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
        dated (fn [s] (re-find #"\b20\d\d-\d\d-\d\d\b" (or s "")))
        rows (concat (d/q '[:find ?n ?doc :where [?v :var/name ?n] [?v :var/doc ?doc] [?v :var/form ?f] [?f :form/ns ?ns] [?ns :ns/file ?file]
                            [(clojure.string/starts-with? ?file "src/")]] db)
                     ;; a namespace's docstring is in its ns form (the facts hold no :ns/doc), read as data
                     (for [[n src] (d/q '[:find ?n ?src :where [?ns :ns/name ?n] [?ns :ns/form ?f] [?f :form/src ?src] [?ns :ns/file ?file]
                                          [(clojure.string/starts-with? ?file "src/")]] db)
                           :let [form (binding [*read-eval* false] (read-string {:read-cond :allow :features #{:clj}} src))
                                 doc (when (seq? form) (some #(when (string? %) %) (take 2 (drop 2 form))))]
                           :when doc]
                       [n doc]))]
    (is (pos? (count rows)) "the facts hold src's docstrings")
    (is (= [] (sort (for [[n doc] rows :when (dated doc)] [n (dated doc)])))
        "no src docstring holds an ISO date")))

(deftest only-measure-reads-the-tick-counter
  ;; task seon.profile/a-call-is-measured-one-way: a call is measured one way, so no src form but
  ;; seon.profile/measure! reads the engine's tick counter (jolt.host/ticks-of or jolt.host/ticks):
  ;; a measurement built beside it is red here. Read from the compiler's facts
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
        callers (d/q '[:find [?n ...] :in $ [?r ...] :where [?f :form/refs ?r] [?f :form/defines ?v] [?v :var/name ?n]
                       [?f :form/ns ?ns] [?ns :ns/file ?file] [(clojure.string/starts-with? ?file "src/")]]
                     db ['jolt.host/ticks-of 'jolt.host/ticks])]
    (is (= ['seon.profile/measure!] (vec callers)))))

(deftest a-runs-lifecycle-is-read-once
  ;; task seon.task/a-runs-lifecycle-is-read-once: a run's lifecycle sets are declared once, on
  ;; :seon.run/status's schema, and read once, by seon.task (finished, judged, failed), which every
  ;; view and judge requires; a second reader is red here. Each src form's recorded source read as
  ;; data (the compiler's facts: :form/src), never a text search
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
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

(deftest a-bound-is-a-config-key
  ;; task seon.config/a-bound-is-a-config-key (AGENTS §5, only documented config): every limit,
  ;; bound or default is a config key. Read from the compiler's facts (each src form's recorded
  ;; source, as data): a top-level def of a number, and a literal count over 2 given to take,
  ;; take-last, drop or drop-last, is a site; each site is named in docs/config.md#constants
  ;; with its reason, and a name there whose site is gone is red too. A foreign interface's
  ;; constant, named in upper case as its header names it, is the interface's, not a bound
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
        forms (d/q '[:find ?nsn ?src :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn] [?ns :ns/file ?file]
                     [(clojure.string/starts-with? ?file "src/")]] db)
        read-in (fn [nsn src] (require nsn)
                  (binding [*read-eval* false *ns* (the-ns nsn)] (read-string {:read-cond :allow :features #{:clj}} src)))
        foreign? (fn [s] (not (re-find #"[a-z]" (name s))))
        sites (into (sorted-set)
                    (for [[nsn src] forms
                          :let [f (read-in nsn src)
                                v (when (and (seq? f) (symbol? (second f))) (symbol (str nsn) (name (second f))))]
                          :when v
                          :when (or (and (#{'def 'defonce} (first f)) (number? (last f)) (not (foreign? v)))
                                    (some #(and (seq? %) (symbol? (first %)) (#{"take" "take-last" "drop" "drop-last"} (name (first %)))
                                                (number? (second %)) (> (second %) 2))
                                          (tree-seq coll? seq f)))]
                      v))
        page (slurp (str (seon.config/checkout) "/docs/config.md"))
        section (second (re-find #"(?s)## Constants\n(.*?)(?:\n## |\z)" page))
        ;; the names each bullet lists before its reason (its text up to the first `: `)
        named (into (sorted-set)
                    (for [l (clojure.string/split-lines (or section "")) :when (clojure.string/starts-with? l "- ")
                          [_ n] (re-seq #"`([a-z][\w.-]*/[\w*!?<>=-]+)`" (first (clojure.string/split l #": " 2)))]
                      (symbol n)))]
    (is (some? section) "docs/config.md has its Constants section")
    (is (= #{} (apply disj sites named)) "every bound left in src is named in docs/config.md#constants with its reason")
    (is (= #{} (apply disj named sites)) "and every name there is still such a site")))

(deftest an-edge-asks-one-way
  ;; task seon.step/an-edge-asks-one-way: an edge asks a proc one way, seon.step/ask-edge, which
  ;; answers a closed channel at once; no src form outside seon.step puts a request carrying its
  ;; promise (:p) on a channel itself. src's forms read as data from the compiler's facts
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
        forms (d/q '[:find ?nsn ?src :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn] [?ns :ns/file ?file]
                     [(clojure.string/starts-with? ?file "src/")]] db)
        read-in (fn [nsn src] (require nsn)
                  (binding [*read-eval* false *ns* (the-ns nsn)] (read-string {:read-cond :allow :features #{:clj}} src)))
        carries-p? (fn [x] (or (and (map? x) (contains? x :p))
                               (and (seq? x) (symbol? (first x)) (= "assoc" (name (first x))) (some #{:p} (take-nth 2 (drop 2 x))))))
        ;; not covered: a request bound first (let [m (assoc x :p p)] ...) or built by merge or into
        puts (into (sorted-set)
                   (for [[nsn src] forms
                         :let [f (read-in nsn src)]
                         :when (some #(and (seq? %) (symbol? (first %)) (#{">!!" "put!" ">!" "offer!"} (name (first %))) (carries-p? (nth % 2 nil)))
                                     (tree-seq coll? seq f))]
                     nsn))]
    (is (= #{'seon.step} puts))))

(deftest an-errors-message-is-read-one-way
  ;; task seon.step/an-errors-message-is-read-one-way: an exception's message, or its class, is
  ;; seon.step/error-msg; no src form outside seon.step writes (or (ex-message e) (str (type e)))
  ;; again. src's forms read as data from the compiler's facts. Not covered: the same read written
  ;; another way ((.getMessage e), (str (class e)), the two in the other order)
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
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

;; ---- an error names its raiser ---------------------------------------------------------------

(defn- quoted-operations
  "The operations form f (read in namespace nsn) names as quoted symbols: a call resolving to
  seon.contract/error whose operation is (quote sym), and a map literal whose :seon.error/operation
  is. A raiser names itself by its var (#'ns/f), which the compile resolves."
  [nsn f]
  (let [error? #(and (symbol? %) (= #'seon.contract/error (ns-resolve (the-ns nsn) %)))
        quoted #(when (and (seq? %) (= 'quote (first %)) (symbol? (second %))) (second %))]
    (into [] (keep (fn [x] (cond (and (seq? x) (error? (first x))) (quoted (nth x 2 nil))
                                 (map? x) (quoted (get x :seon.error/operation)))))
          (tree-seq coll? seq f))))

(deftest an-error-names-its-raiser-by-its-var
  ;; root, 2026-10-25 (the a-map design, 0 green in 9 runs): the error a model reproduced first named
  ;; 'seon.render.value/show, a namespace renamed away, and the runs searched for it. An error names
  ;; its raiser by its var (#'ns/f, the function it is built in), so the compile refuses one that is
  ;; gone; no src form names one as a quoted symbol. src's forms read as data from the compiler's facts
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
        forms (d/q '[:find ?nsn ?src :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn] [?ns :ns/file ?file]
                     [(clojure.string/starts-with? ?file "src/")]] db)
        read-in (fn [nsn src] (require nsn)
                  (binding [*read-eval* false *ns* (the-ns nsn)] (read-string {:read-cond :allow :features #{:clj}} src)))]
    (testing "a quoted operation is seen, in a call and in a map"
      (is (= '[seon.render.value/show] (quoted-operations 'seon.fx '(defn f [] (contract/error :x/y 'seon.render.value/show "m")))))
      (is (= '[seon.render.value/show] (quoted-operations 'seon.fx '(defn f [] {:seon.error/type :x/y :seon.error/operation 'seon.render.value/show}))))
      (is (= [] (quoted-operations 'seon.fx '(defn f [] (contract/error :x/y #'seon.fx/build "m")))) "a var is not"))
    (is (= [] (vec (for [[nsn src] forms op (quoted-operations nsn (read-in nsn src))] [nsn op])))
        "no src error names its operation as a quoted symbol")))

(deftest git-is-read-through-fx
  ;; task seon.fx/git-is-read-through-fx: git runs in one place, seon.fx/git-run (read two ways, git!:
  ;; a failure throws its words, and git-try: git's no is nil), and a checkout is told by its .git
  ;; through checked-out?; no other src form names the git command or a repository's own files (".git").
  ;; src's forms read as data from the compiler's facts
  (let [db (runner/program-db (seon.config/checkout) (ts/kept-program-store))
        forms (d/q '[:find ?nsn ?src :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn] [?ns :ns/file ?file]
                     [(clojure.string/starts-with? ?file "src/")]] db)
        read-in (fn [nsn src] (require nsn)
                  (binding [*read-eval* false *ns* (the-ns nsn)] (read-string {:read-cond :allow :features #{:clj}} src)))
        ;; a form names git when it holds the literal "git" or ".git", or is a call naming
        ;; clojure.java.shell/sh (called, or applied) with a string whose words include git (a shell
        ;; line: zsh -c "... git -C ..."). Other process runners (ProcessBuilder) are not seen
        sh? #(and (symbol? %2) (= #'clojure.java.shell/sh (ns-resolve (the-ns %1) %2)))
        names-git? (fn [nsn f]
                     (some (fn [x] (or (#{"git" ".git"} x)
                                       (and (seq? x) (some #(sh? nsn %) x)
                                            (some #(and (string? %) (some #{"git"} (clojure.string/split % #"[^A-Za-z0-9_.-]+"))) (tree-seq coll? seq x)))))
                           (tree-seq coll? seq f)))
        readers (into (sorted-set)
                      (for [[nsn src] forms
                            :let [f (read-in nsn src)
                                  v (when (and (seq? f) (symbol? (second f))) (symbol (str nsn) (name (second f))))]
                            :when (names-git? nsn f)]
                        (or v nsn)))]
    (testing "a shell line that runs git is seen, called or applied (checkout-at's old form)"
      (is (names-git? 'seon.fx '(defn x [d] (sh/sh "zsh" "-c" "rm -rf d && git -C \"$2\" archive HEAD | tar -x" "x" d))))
      (is (names-git? 'seon.fx '(defn x [d] (apply sh/sh "zsh" "-c" "rm -rf d && git -C \"$2\" archive HEAD | tar -x" "x" [d])))))
    (testing "a shell line that names no git is not"
      (is (not (names-git? 'seon.fx '(defn x [d] (sh/sh "zsh" "-c" "tar -x -C \"$1\"" "x" d))))))
    ;; seon.config's own-branch reader tests whether the checkout's .git is a file (a worktree): a file
    ;; test at load, before any proc runs, not a read of git
    (is (= #{'seon.fx/git-run 'seon.fx/checked-out? 'seon.config/reader} readers))))
