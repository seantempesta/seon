(ns seon.main-test
  "A root holds no program it was not given (GUARDS #50): a fresh store, as seon.system/start!
  opens it by default, holds the schema and nothing else; root's warm-up needs no seed."
  (:require [seon.lib] [clojure.test :refer [deftest is testing]]
            [clojure.java.shell] [datahike.api :as d]
            [seon.db :as pdb] [seon.program] [seon.system :as root] [seon.main] [seon.agent] [seon.test-store :as ts] [test-runner :as runner] [my.test]))

(defn- program-datoms [db]
  (vec (mapcat #(d/datoms db :aevt %) [:ns/name :form/id :var/name :task/name])))

(defn- fresh-store
  "A fresh store on disk of this test's own, the last resort: what is proved is what a
  fresh store holds when a root creates it. Answers [path opened]."
  []
  (let [path (str (System/getProperty "java.io.tmpdir") "/seon-main-test-" (random-uuid))]
    [path (pdb/open! :lmdb path 0 :db)]))

(deftest ^{:seon.test/estimate {:ticks 30000000}} a-fresh-root-holds-no-program-it-was-not-given
  (let [[path {:keys [conn seeded?]}] (fresh-store)]
    (try
      (is seeded?)
      (is (= [] (pdb/seed-tx 0)) "no example program unless one is asked for")
      (is (= [] (program-datoms @conn)))
      (is (contains? (d/schema @conn) :var/name) "the schema is there")
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest ^{:seon.test/estimate {:ticks 30000000}} warm-needs-no-seed
  (let [[path {:keys [conn]}] (fresh-store)
        t (:max-tx @conn)]
    (try
      (is (= {:doc "warm" :task {:task/name :seon.warm/tidy :task/refs [{:var/name 'seon.warm/f}]}} (#'root/warm! conn))
          "its reads found its own definition and task")
      (is (= t (:max-tx @conn)) "warm-up commits nothing")
      (is (= [] (program-datoms @conn)))
      (is (nil? (find-ns 'seon.warm)) "its scratch namespace is gone")
      (finally (d/release conn) (seon.lib/remove-tree! path)))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} root-boots-without-test-fixtures
  ;; the compiler's facts of the checkout (src and test imported as program facts): the
  ;; release binary compiles what src requires, so no src namespace requires one whose file is
  ;; in test/ (the check driver, the e2e runner, the scripted model's fixtures); a scripted root loads its fixture from the checkout by name
  (let [db (seon.agent/db)
        file (into {} (d/q '[:find ?n ?f :where [?e :ns/name ?n] [?e :ns/file ?f]] db))
        in? (fn [dir n] (some-> (file n) (clojure.string/starts-with? dir)))
        reqs (d/q '[:find ?n ?r :where [?e :ns/name ?n] [?e :ns/form ?f] [?f :form/requires ?r]] db)]
    (is (in? "src/" 'seon.system) "the facts hold src's namespaces")
    (is (in? "test/" 'seon.fixtures) "and test's")
    (is (= #{} (set (for [[n r] reqs :when (and (in? "src/" n) (in? "test/" r))] [n r])))
        "no src namespace requires a test/ one")))

(deftest ^{:seon.test/estimate {:ticks 5000000}} only-measure-reads-the-tick-counter
  ;; task seon.profile/a-call-is-measured-one-way: a call is measured one way, so no src form but
  ;; seon.profile/measure! reads the engine's tick counter (jolt.host/ticks-of or jolt.host/ticks):
  ;; a measurement built beside it is red here. Read from the compiler's facts
  (let [db (seon.agent/db)
        callers (d/q '[:find [?n ...] :in $ [?r ...] :where [?f :form/refs ?r] [?f :form/defines ?v] [?v :var/name ?n]
                       [?f :form/ns ?ns] [?ns :ns/file ?file] [(clojure.string/starts-with? ?file "src/")]]
                     db ['jolt.host/ticks-of 'jolt.host/ticks])]
    (is (= ['seon.profile/measure!] (vec callers)))))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-task-is-never-written-gate-true
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a merge's gate runs the process tests its change reaches before
  ;; it merges, nobody asking (seon.reconcile's :gate), so :seon.task/gate? asks for nothing: no src form names it
  ;; and no schema declares it (the forms seon.config/schema-forms holds are the schemas' own text). Read from the
  ;; compiler's facts (:form/keywords), never a text search
  (let [db (seon.agent/db)
        named (d/q '[:find [?n ...] :in $ ?k :where [?f :form/keywords ?k] [?f :form/defines ?v] [?v :var/name ?n]
                     [?f :form/ns ?ns] [?ns :ns/file ?file] [(clojure.string/starts-with? ?file "src/")]]
                   db :seon.task/gate?)]
    (is (= [] (vec (sort named))))))

(defn- forms-with
  "The [nsn src] of db's src forms whose attribute attr holds one of vs: an index seek per value, so a law reads only the
  forms that can break it, never the whole program."
  [db attr vs]
  (d/q [:find '?nsn '?src :in '$ '?a '[?v ...]
        :where '[?f ?a ?v] '[?f :form/src ?src] '[?f :form/ns ?ns] '[?ns :ns/name ?nsn] '[?ns :ns/file ?file]
        '[(clojure.string/starts-with? ?file "src/")]]
       db attr (vec vs)))

(defn- read-form
  "Form src as data, read in the namespace of the program under judgement (loaded, so its alias keywords resolve) and as
  the compiler reads it (:clj). A fixture namespace has no file to load: it is read in user, with qualified names."
  [fixture? nsn src]
  (when-not fixture? (require nsn))
  (binding [*read-eval* false *ns* (the-ns (if fixture? 'user nsn))] (read-string {:read-cond :allow :features #{:clj}} src)))

;; task seon.task/a-runs-lifecycle-is-read-once: a task's outcome sets are declared once, on :seon.task/outcome's schema,
;; and read once, by seon.task (finished, judged, failed), which every view and judge requires; a second reader is red.
;; Only a src form naming the keyword can call a schema reader (properties, enum-values) on it, so only those forms
;; (an index seek on :form/keywords) are read as data. Not covered: a call built by syntax-quote
(defn- outcome-readers
  "The src namespaces holding a call of a schema reader (properties, enum-values) on :seon.task/outcome."
  [db fixture?]
  (into #{} (keep (fn [[nsn src]]
                    (when (some #(and (seq? %) (symbol? (first %)) (#{"properties" "enum-values"} (name (first %)))
                                      (= :seon.task/outcome (second %)))
                                (tree-seq coll? seq (read-form fixture? nsn src)))
                      nsn)))
        (forms-with db :form/keywords [:seon.task/outcome])))

;; Estimate: the fixture is 2 namespaces of 4 one-line defns, imported at ~1M ticks each, then 2 forms read (~0.1M each).
;; The program: one seek of :form/keywords (~0.05M) joined to ~40 forms' src, ns and file (5 seeks x 2k ticks = 0.4M),
;; and ~40 forms read and walked (~0.1M each = 4M); 4.5M, with the fixture 6M
(deftest ^{:seon.test/estimate {:ticks 12000000}} a-tasks-outcomes-are-read-once
  (let [fix (my.test/with (my.test/world)
              (my.test/code 'app.out (str "(ns app.out)\n(defn properties [k] k)\n(defn enum-values [k] k)\n"
                                          "(defn a [] (properties :seon.task/outcome))\n"
                                          "(defn b [] (enum-values :seon.task/outcome))\n"))
              (my.test/code 'app.fine (str "(ns app.fine)\n(defn properties [k] k)\n"
                                           "(defn c [] (properties :seon.task/other))\n"
                                           "(defn d [] [:seon.task/outcome])\n")))]
    (is (= #{'app.out} (outcome-readers fix true)) "a reader call on the key is seen; another key, or the key not read, is not")
    (is (= #{'seon.task} (outcome-readers (seon.agent/db) false)))))

;; docs/config.md#what-waits-for-a-rebuild: a top-level def of src that reads a config key keeps the value it loaded with
;; whatever a branch states, so its key carries :seon.config/at-load, and seon.config/waiting can say so. Only a form
;; naming seon.config/value or resolve (an index seek on :form/refs) can read one, so only those forms' text is searched
(defn- read-at-load-keys
  "The config keys the top-level defs of db's src read through seon.config/value or resolve."
  [db]
  (into (sorted-set)
        (for [[_ src] (forms-with db :form/refs ['seon.config/value 'seon.config/resolve])
              :when (re-find #"^\(def(once)?\s" src)
              :when (re-find #"(config|cfg)/(value|resolve) " src)
              [_ k] (re-seq #"\(\S*/(?:value|resolve) (:seon\.config\.[\w./?!*+-]+)" src)]
          (keyword (subs k 1)))))

;; Estimate: the fixture is 1 namespace of 3 defns (~1.5M ticks to import). The program: 2 seeks of :form/refs (0.1M) joined
;; to ~320 forms' src, ns and file (5 seeks x 2k = 3.2M), 320 regex searches of ~500 chars (0.01M each = 3M); 7M, with the fixture 9M
(deftest ^{:seon.test/estimate {:ticks 15000000}} a-key-read-at-load-says-so
  (let [fix (my.test/with (my.test/world)
              (my.test/code 'app.cfgs (str "(ns app.cfgs (:require [seon.config :as config]))\n"
                                           "(def marked (config/value :seon.config.t/marked))\n"
                                           "(def unmarked (config/value :seon.config.t/unmarked))\n"
                                           "(defn in-a-fn [] (config/value :seon.config.t/per-call))\n")))
        marks {:seon.config.t/marked [:x {:seon.config/at-load true}]}
        unmarked (fn [db marks] (vec (remove #(get-in marks [% 1 :seon.config/at-load]) (read-at-load-keys db))))]
    (is (= [:seon.config.t/marked :seon.config.t/unmarked] (vec (read-at-load-keys fix))) "a top-level def's key is seen; a function's is not")
    (is (= [:seon.config.t/unmarked] (unmarked fix marks)) "one the schema does not mark is named")
    (let [db (seon.agent/db)]
      (is (seq (read-at-load-keys db)))
      (is (= [] (unmarked db seon.config/schema-forms))))))

(deftest ^{:seon.test/estimate {:ticks 100000000}} a-bound-is-a-config-key
  ;; task seon.config/a-bound-is-a-config-key (docs/config.md#constants, only documented config): every limit,
  ;; bound or default is a config key. Read from the compiler's facts (each src form's recorded
  ;; source, as data): a top-level def of a number, and a literal count over 2 given to take,
  ;; take-last, drop or drop-last, is a site; each site is named in docs/config.md#constants
  ;; with its reason, and a name there whose site is gone is red too. A foreign interface's
  ;; constant, named in upper case as its header names it, is the interface's, not a bound
  (let [db (seon.agent/db)
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

;; docs/resolution.md: Jolt compiles a qualified name with no var and fails only when the line runs, so a path no test runs ships it
(deftest ^{:seon.test/estimate {:ticks 100000000}} every-name-a-form-uses-is-a-var
  (let [db (seon.agent/db)
        ;; the program's own: a test names what's gone to say it's gone
        ;; one scan of each attribute's index, joined by set membership (a d/q join over the 113k :form/refs
        ;; datoms cost 600M ticks); most of what is left is decoding that index's nodes on its first read
        src-nss (into #{} (comp (filter #(clojure.string/starts-with? (:v %) "src/")) (map :e)) (d/datoms db :aevt :ns/file))
        forms (into #{} (comp (filter #(contains? src-nss (:v %))) (map :e)) (d/datoms db :aevt :form/ns))
        acct {:refs (into #{} (comp (filter #(contains? forms (:e %))) (map :v)) (d/datoms db :aevt :form/refs))}]
    (run! #(require (symbol %)) (into #{} (comp (keep namespace) (filter #(re-find #"^(seon|my)\." %))) (:refs acct)))
    (is (= [] (seon.program/unknown-refs acct)))))

(def ^:private put-names #{"put!" ">!!" ">!" "offer!"})

(defn- put-vars
  "The vars that put on a channel: the program's own vars so named (one scan of :var/name) and clojure.core.async's publics so named."
  [db]
  (into (set (for [d (d/datoms db :aevt :var/name) :when (put-names (name (:v d)))] (:v d)))
        (for [[n _] (ns-publics 'clojure.core.async) :when (put-names (name n))] (symbol "clojure.core.async" (name n)))))

;; task seon.step/an-edge-asks-one-way: an edge asks a proc one way, seon.step/ask-edge, which answers a closed channel at
;; once; no src form outside seon.step puts a request carrying its promise (:p) on a channel itself. Only a form naming a
;; channel put (an index seek on :form/refs per put var) can, so only those forms are read as data
(defn- request-putters
  "The src namespaces holding a channel put whose request carries :p. Not covered: a request bound first
  (let [m (assoc x :p p)] ...) or built by merge or into."
  [db fixture?]
  (let [carries-p? (fn [x] (or (and (map? x) (contains? x :p))
                               (and (seq? x) (symbol? (first x)) (= "assoc" (name (first x))) (some #{:p} (take-nth 2 (drop 2 x))))))]
    (into (sorted-set)
          (for [[nsn src] (forms-with db :form/refs (put-vars db))
                :when (some #(and (seq? %) (symbol? (first %)) (put-names (name (first %))) (carries-p? (nth % 2 nil)))
                            (tree-seq coll? seq (read-form fixture? nsn src)))]
            nsn))))

;; Estimate: the fixture is 2 namespaces of 3 defns (2 x 0.35M ticks to import). The program: a scan of 6.4k :var/name datoms
;; (1M), ~5 seeks of :form/refs (0.25M) joined to ~25 forms' src, ns and file (0.25M) and read (~0.05M each = 1.2M); 3M, with the fixture 4M; the fixture imports and the walks cost 1.5M more than counted, so 6M
(deftest ^{:seon.test/estimate {:ticks 6000000}} an-edge-asks-one-way
  (let [fix (my.test/with (my.test/world)
              (my.test/code 'app.edge1 (str "(ns app.edge1 (:require [clojure.core.async :as a]))\n"
                                            "(defn ask [ch p] (a/>!! ch {:p p}))\n"
                                            "(defn ask2 [ch p] (a/put! ch (assoc {} :p p)))\n"))
              (my.test/code 'app.edge2 (str "(ns app.edge2 (:require [clojure.core.async :as a]))\n"
                                            "(defn tell [ch] (a/>!! ch {:x 1}))\n")))]
    (is (= #{'app.edge1} (request-putters fix true)) "a put carrying :p is seen, in a map and in an assoc; one without is not")
    (is (= #{'seon.step} (request-putters (seon.agent/db) false)))))

(defn- message-writers
  "The src namespaces writing (or (ex-message e) (str (type e))): task seon.step/an-errors-message-is-read-one-way, an
  exception's message, or its class, is seon.step/error-msg. Only a form referencing clojure.core/ex-message (an index
  seek on :form/refs) can hold the shape, and only one whose text shows `(or (ex-message` is read as data. Not covered: the same
  read written another way ((.getMessage e), (str (class e)), the two in the other order)."
  [db fixture?]
  (let [shape? (fn [x] (and (seq? x) (= 'or (first x))
                            (let [[a b] (rest x)]
                              (and (seq? a) (symbol? (first a)) (= "ex-message" (name (first a)))
                                   (seq? b) (symbol? (first b)) (= "str" (name (first b)))
                                   (seq? (second b)) (symbol? (first (second b))) (= "type" (name (first (second b))))))))]
    (into (sorted-set)
          (for [[nsn src] (forms-with db :form/refs ['clojure.core/ex-message])
                :when (re-find #"\(or\s+\(\S*ex-message" src)
                :when (some shape? (tree-seq coll? seq (read-form fixture? nsn src)))]
            nsn))))

;; Estimate: the fixture is 2 defns (0.35M ticks). The program: 1 seek (0.05M) joined to 54 forms' src, ns and file (0.5M; the joins and fixture import cost 2x the count, so 3.5M),
;; 54 regex searches (~0.002M each = 0.1M), the ~3 that match read (0.15M); 0.8M, with the fixture 1.2M
(deftest ^{:seon.test/estimate {:ticks 3500000}} an-errors-message-is-read-one-way
  (let [fix (my.test/with (my.test/world)
              (my.test/code 'app.msg (str "(ns app.msg)\n"
                                          "(defn bad [e] (or (ex-message e) (str (type e))))\n"
                                          "(defn fine [e] (or (ex-message e) \"none\"))\n")))]
    (is (= #{'app.msg} (message-writers fix true)) "the message-or-class shape is seen; a message with another default is not")
    (is (= #{'seon.step} (message-writers (seon.agent/db) false)))))

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

;; Estimate: the fixture is 1 namespace of 3 defns (~1.5M ticks to import). The program: 2 seeks (0.1M) joined to the ~600 forms
;; that raise an error (5 seeks x 2k = 6M), each tested for a quoted name by one regex (~0.005M = 3M), the few that pass
;; read as data (~20 x 0.1M = 2M); 11M, with the fixture 13M
(deftest ^{:seon.test/estimate {:ticks 25000000}} an-error-names-its-raiser-by-its-var
  ;; root, 2026-10-25 (the a-map design, 0 green in 9 runs): the error a model reproduced first named
  ;; 'seon.render.value/show, a namespace renamed away, and the runs searched for it. An error names
  ;; its raiser by its var (#'ns/f, the function it is built in), so the compile refuses one that is
  ;; gone; no src form names one as a quoted symbol. Only a form calling seon.contract/error or holding the
  ;; :seon.error/operation key (index seeks) can, and only one with a quoted name in its text is read as data
  (let [raisers (fn [db fixture?]
                  (vec (distinct (for [[nsn src] (concat (forms-with db :form/refs ['seon.contract/error])
                                                         (forms-with db :form/keywords [:seon.error/operation]))
                                       :when (re-find #"'[a-z]" src)
                                       op (quoted-operations (if fixture? 'user nsn) (read-form fixture? nsn src))]
                                   [nsn op]))))
        fix (my.test/with (my.test/world)
              (my.test/code 'app.raise (str "(ns app.raise)\n"
                                            "(defn f [] (seon.contract/error :x/y 'app.raise/f \"m\"))\n"
                                            "(defn g [] {:seon.error/type :x/y :seon.error/operation 'app.raise/g})\n"
                                            "(defn h [] (seon.contract/error :x/y #'app.raise/h \"m\"))\n")))]
    (testing "a quoted operation is seen, in a call and in a map"
      (is (= '[seon.render.value/show] (quoted-operations 'seon.fx '(defn f [] (contract/error :x/y 'seon.render.value/show "m")))))
      (is (= '[seon.render.value/show] (quoted-operations 'seon.fx '(defn f [] {:seon.error/type :x/y :seon.error/operation 'seon.render.value/show}))))
      (is (= [] (quoted-operations 'seon.fx '(defn f [] (contract/error :x/y #'seon.fx/build "m")))) "a var is not"))
    (is (= '[[app.raise app.raise/f] [app.raise app.raise/g]] (sort (raisers fix true))) "found in the program's forms, a var excepted")
    (is (= [] (raisers (seon.agent/db) false)) "no src error names its operation as a quoted symbol")))

(defn- git-readers
  "The vars of db's src that run git through a shell runner (seon.lib/sh, or the clojure.java.shell/sh it runs): a form
  holding the literal \"git\" or \".git\", or a call of a runner with a string whose words include git (a shell line:
  zsh -c \"... git -C ...\", called or applied). Only a form referencing a runner (an index seek on :form/refs) can run
  git, so only those are read. Other process runners (ProcessBuilder) are not seen, nor is a file test of .git outside a runner call."
  [db fixture?]
  (let [sh? #(and (symbol? %2) (#{#'clojure.java.shell/sh #'seon.lib/sh} (ns-resolve (the-ns %1) %2)))
        names-git? (fn [nsn f]
                     (some (fn [x] (or (#{"git" ".git"} x)
                                       (and (seq? x) (some #(sh? nsn %) x)
                                            (some #(and (string? %) (some #{"git"} (clojure.string/split % #"[^A-Za-z0-9_.-]+"))) (tree-seq coll? seq x)))))
                           (tree-seq coll? seq f)))]
    (into (sorted-set)
          (for [[nsn src] (forms-with db :form/refs ['seon.lib/sh 'clojure.java.shell/sh])
                :let [f (read-form fixture? nsn src)
                      v (when (and (seq? f) (symbol? (second f))) (symbol (str nsn) (name (second f))))]
                :when (names-git? (if fixture? 'user nsn) f)]
            (or v nsn)))))

;; task seon.fx/git-is-read-through-fx: git runs in one place, seon.fx/git-run (read two ways, git!: a failure throws its
;; words, and git-try: git's no is nil); no other src form runs git through a shell runner.
;; Estimate: the fixture is 1 namespace of 4 defns (0.4M ticks). The program: 2 seeks (0.1M) joined to ~92 forms' src, ns and
;; file (0.9M) and read and walked (~0.03M each = 2.8M); 3.8M, with the fixture 4.2M
(deftest ^{:seon.test/estimate {:ticks 4500000}} git-is-read-through-fx
  (let [fix (my.test/with (my.test/world)
              (my.test/code 'app.gits (str "(ns app.gits)\n"
                                           "(defn direct [d] (seon.lib/sh \"git\" \"-C\" d \"log\"))\n"
                                           "(defn applied [d] (apply seon.lib/sh \"zsh\" \"-c\" \"cd d && git archive HEAD\" [d]))\n"
                                           "(defn through-fx [d] (clojure.string/trim d))\n"
                                           "(defn other [d] (seon.lib/sh \"tar\" \"-x\" d))\n")))]
    (is (= '#{app.gits/direct app.gits/applied} (git-readers fix true)) "a runner call naming git is seen, called or applied; one naming tar, or the fx way, is not")
    (is (= #{'seon.fx/git-run} (git-readers (seon.agent/db) false)))))

(deftest ^{:seon.test/estimate {:ticks 10000000}} every-shell-runs-through-lib-sh
  ;; task seon.system/a-boot-runs-no-shell-of-its-own: clojure.java.shell/sh called off a fiber copies a child's pipes on
  ;; futures, threads of Clojure's send-off pool that root does not declare (seon.server-test/a-done-shells-on-no-thread-of-roots);
  ;; seon.lib/sh runs it on a fiber, and no other src form names it (docs/machine.md#processes). Read from the compiler's facts
  ;; (:form/refs: what a form names, a quoted name being data and no reference), an index seek: reading every src form as data
  ;; was ~150M ticks of the test's own
  (let [callers-in (fn [db] (set (d/q '[:find [?v ...] :where [?f :form/refs clojure.java.shell/sh] [?f :form/ns ?ns] [?ns :ns/file ?file]
                                         [(clojure.string/starts-with? ?file "src/")] [?f :form/defines ?d] [?d :var/name ?v]] db)))
        probe (my.test/with (my.test/world)
                (my.test/code 'app.shq (str "(ns app.shq (:require [clojure.java.shell]))\n"
                                            "(def quoted '{clojure.java.shell/sh :bin})\n"
                                            "(defn calls [] (clojure.java.shell/sh \"true\"))\n")))]
    (is (= #{'app.shq/calls} (set (d/q '[:find [?v ...] :where [?f :form/refs clojure.java.shell/sh] [?f :form/defines ?d] [?d :var/name ?v]] probe)))
        "a call naming it is a reference; a quoted name is not")
    (is (= #{'seon.lib/sh} (callers-in (seon.agent/db))))))

;; docs/merging.md#a-session-proves-through-root: bin/prove --root prints every kind of item as it lands, the tree's build and
;; seed by their kind
(deftest ^{:seon.test/estimate {:ticks 1000000}} bin-prove-root-prints-each-kind-of-verdict
  (let [line @#'seon.main/verdict-line]
    (is (= "PASS a.b-test/t 315 ms" (line {:test 'a.b-test/t :digest "d" :status :pass :ms 315.4})))
    (is (= "PASS build of tree T 46650 ms" (line {:test [:build "T"] :digest "T" :kind :build :status :pass :ms 46650.48})))
    (is (clojure.string/starts-with? (line {:test "laws" :digest "dl" :kind :step :status :fail :failures [{:type :fail :message "m"}]}) "RED laws (fail)"))))

;; docs/merging.md#a-tests-tick-budget: every test that states no estimate is listed, with what it spends, costliest first,
;; for its area to state one from what it must do (plans: tests-spent-ticks); one that states an estimate is not. T1: the
;; world's two tests, one given an estimate.
(deftest ^{:seon.test/estimate {:ticks 20000000}} every-test-stating-no-estimate-is-listed
  (let [db (my.test/with (my.test/world)
             (my.test/code 'app.est-test (str "(ns app.est-test (:require [clojure.test :refer [deftest is]]))\n"
                                              "(deftest ^{:seon.test/estimate {:ticks 1000}} stated (is true))\n"
                                              "(deftest unstated (is true))\n")))
        u (runner/unestimated db)
        names (set (map :test u))]
    (is (contains? names 'app.est-test/unstated) "a test stating no estimate is listed")
    (is (contains? names 'app.calc-test/add-adds) "the world's tests, which state none, too")
    (is (not (contains? names 'app.est-test/stated)) "a test stating one is not")
    (is (= '[app.est-test app.calc-test] [(:ns (first (filter #(= 'app.est-test/unstated (:test %)) u))) (:ns (first (filter #(= 'app.calc-test/add-adds (:test %)) u)))])
        "each with its namespace, the area that states its estimate")))
