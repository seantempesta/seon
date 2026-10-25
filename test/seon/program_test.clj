(ns seon.program-test
  "The program as the compiler knows it (owner, 2026-10-21; inv4's design): each form an
  agent evaluates is recorded with what Jolt's analyzer says it defines, declares and
  references (jolt.analyzer/*unit-tap*) and a readback of what it set, never from its text.
  Every kind of definition (defn, defmulti and defmethod, defprotocol and extend-type,
  defrecord, do-nested defs, a new namespace's ns form); scratch namespaces stay scratch; a
  redefinition supersedes the form it replaces and takes its place; main loads forms in the
  order of their real references; a test reaches what it calls; banned calls and inline
  definitions are recorded for the merge to judge; a file analyzed without running gives
  the REPL's facts."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.program :as program] [seon.docs]
            [seon.proofs :as proofs]
            [seon.fx :as fx]
            [seon.merge :as pm]
            [seon.source :as source]
            [clojure.java.shell :as sh]
            [test-runner :as runner]
            [jolt.analyzer]))

(defn- empty-db [] (ts/test-db))

(defn- ns-form? [form nsx]
  (boolean (and (seq? form) (symbol? (first form)) (= #'clojure.core/ns (ns-resolve nsx (first form))))))

(defn eval-account
  "Evaluate src (one form) in namespace nsym as the agent's REPL does (seon.repl/wrap-record):
  the tap around the eval, then the form's account, or nil."
  [nsym src]
  (let [nsx (program/ensure-ns! nsym)
        form (binding [*ns* nsx] (read-string src))
        acc (volatile! {:nodes [] :before {}})
        after (binding [*ns* nsx jolt.analyzer/*unit-tap* (program/tap acc)] (eval form) (ns-name *ns*))]
    (program/account (assoc @acc :src src :form form :ns nsym :ns-after after :ns-form? (ns-form? form nsx) :ran? true
                            :ns-meta (when (ns-form? form nsx) (meta (second form)))))))

(defn record
  "db after recording each [nsym src] as the agent does (one form, one transaction)."
  [db & forms]
  (reduce (fn [db [nsym src]]
            (if-let [a (eval-account nsym src)] (d/db-with db (program/record-tx db a)) db))
          db (partition 2 forms)))

(defn- in-ns-forms
  "The live forms of namespace nsym on db (the test store's example program is beside them)."
  [db nsym] (filterv #(= nsym (:ns %)) (program/live-forms db)))

(defn- var-row [db sym] (d/pull db '[:var/kind :var/contract :var/doc {:var/form [:form/src :form/order]}] [:var/name sym]))

(deftest a-new-namespace-is-program-with-its-ns-form-as-written
  (let [src "(ns app.pt-new\n  (:require [clojure.string :as s :refer [join]]))"
        db (record (empty-db) 'user src)]
    (is (= src (d/q '[:find ?s . :where [?n :ns/name app.pt-new] [?n :ns/form ?f] [?f :form/src ?s]] db)) "the ns form, byte for byte")
    (is (= '#{clojure.string} (set (d/q '[:find [?r ...] :where [?f :form/src] [?f :form/requires ?r]] db))) "its requires, from the compiler")
    (testing "a def in it is recorded with its resolved references"
      (let [db (record db 'app.pt-new "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x]  (s/join [(* 2 x)]))")]
        (is (= {:var/kind :fn :var/contract "[:=> [:cat :int] :int]"
                :var/form {:form/src "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x]  (s/join [(* 2 x)]))"}}
               (update (var-row db 'app.pt-new/twice) :var/form dissoc :form/order)))
        (is (= '#{clojure.string/join clojure.core/* clojure.core/defn} (set (d/q '[:find [?r ...] :where [?v :var/name app.pt-new/twice] [?v :var/form ?f] [?f :form/refs ?r]] db)))
            "s/join resolved through the alias, * to clojure.core's, and the macro it expanded")))
    (testing "a contract holding a function is recorded as written, so it reads back as data"
      ;; a live agent's (defn ... {:malli/schema [:=> [:cat [:fn pos?]] :int]} ...) was recorded
      ;; from the evaluated value, #object[...], and every later agent on that branch died
      ;; reading it (seon.program/schema-dependents, 2026-10-21)
      (let [src "(defn half {:malli/schema [:=> [:cat [:fn pos?]] :int]} [x] (quot x 2))"
            db (record db 'app.pt-new src)
            c (:var/contract (var-row db 'app.pt-new/half))]
        (is (= "[:=> [:cat [:fn pos?]] :int]" c))
        (is (= '[:=> [:cat [:fn pos?]] :int] (clojure.edn/read-string c)))))
    (testing "in-ns alone is no program: nothing was defined"
      (is (nil? (eval-account 'user "(in-ns 'app.pt-other)"))))))

(deftest user-and-scratch-namespaces-stay-scratch
  (is (nil? (eval-account 'user "(def scratch-x 1)")))
  (is (nil? (eval-account 'user "(ns ^:scratch app.pt-scratch)")))
  (alter-meta! (the-ns 'app.pt-scratch) assoc :scratch true)
  (is (nil? (eval-account 'app.pt-scratch "(defn h [x] x)")) "a def in a ^:scratch namespace")
  (let [own (create-ns 'agent.pt-a1)]
    (alter-meta! own assoc :scratch true)
    (is (nil? (eval-account 'agent.pt-a1 "(def y 2)")) "the agent's own namespace")))

(deftest a-form-records-exactly-what-the-compiler-defined
  (let [a (fn [src] (eval-account 'app.pt-kinds src))
        defs (fn [acct] (into {} (map (juxt :var :kind)) (:defs acct)))]
    (is (= '{app.pt-kinds/a :value app.pt-kinds/g :fn} (defs (a "(do (def a 1) (defn g [] a))"))) "do-nested defs")
    (is (= '{app.pt-kinds/m :multi} (defs (a "(defmulti m :k)"))))
    (is (= '[{:multi app.pt-kinds/m :dispatch ":a"}] (:methods (a "(defmethod m :a [x] (g))"))) "a method: [multi dispatch]")
    (is (= '{app.pt-kinds/P :protocol app.pt-kinds/p1 :protocol-fn} (defs (a "(defprotocol P (p1 [x]))"))))
    (is (empty? (:banned (a "(defprotocol P2 (p2 [x]))"))) "defprotocol no longer expands to alter-var-root (jolt/def-tap)")
    (is (= '[{:type "String" :protocol app.pt-kinds/P}] (:impls (a "(extend-type String P (p1 [x] 1))"))) "an extension: [protocol type]")
    (is (= '{app.pt-kinds/R :type app.pt-kinds/->R :ctor app.pt-kinds/map->R :ctor} (defs (a "(defrecord R [x] P (p1 [_] x))")))
        "a record's constructors need no contract of their own")
    (is (= '{app.pt-kinds/t :test} (defs (a "(clojure.test/deftest t (clojure.test/is (= 1 1)))"))))
    (is (= '{app.pt-kinds/mm :macro} (defs (a "(defmacro mm [x] x)"))))
    (is (= '#{app.pt-kinds/later} (:declares (a "(declare later)"))))
    (is (= '{app.pt-kinds/once :value} (defs (a "(defonce once 1)"))))
    (is (nil? (a "(defonce once 2)")) "readback: a defonce that sets nothing records nothing")
    (is (nil? (a "(+ 1 2)")) "an expression is no program")
    (is (:env? (program/unit-account (jolt.analyzer/analyze (jolt.host/ctx-for-ns "app.pt-kinds") '(require '[clojure.set :as set])))))))

(deftest banned-calls-and-inline-definitions-are-recorded
  (let [acct (eval-account 'app.pt-ban "(defn sneaky [] (alter-var-root #'clojure.core/*print-length* (constantly 3)) (def inner 1))")]
    (is (= '#{clojure.core/alter-var-root} (:banned acct)) "a reference to alter-var-root, by resolved var")
    (is (= '#{app.pt-ban/inner} (:inline-defs acct)) "a def inside a function body"))
  (is (empty? (:banned (eval-account 'app.pt-ban "(defn fine [m] (assoc m :intern 1))"))) "a keyword named like it is nothing"))

(deftest the-keywords-a-form-names-are-recorded
  ;; root's gate (approved): :form/keywords, the data a function operates on, recorded by the
  ;; analyzer from the read it already does: its code's qualified keywords and its contract's
  (let [src "(defn kw-user {:malli/schema [:=> [:cat [:map [:app.kw/thing :int]]] :app.kw/count]} [m] (assoc m :app.kw/count (:app.kw/thing m) :plain 1 :listed '[:app.kw/listed]))"
        acct (eval-account 'app.pt-kw src)
        db (record (empty-db) 'app.pt-kw src)]
    (is (= #{:app.kw/thing :app.kw/count :app.kw/listed} (:keywords acct))
        "its code's (a constant, a keyword called, quoted data) and its contract's qualified keywords, the unqualified left out")
    (is (= #{:app.kw/thing :app.kw/count :app.kw/listed} (set (d/q '[:find [?k ...] :where [?v :var/name app.pt-kw/kw-user] [?v :var/form ?f] [?f :form/keywords ?k]] db)))
        "as facts of its form")
    (is (= ['app.pt-kw/kw-user] (seon.docs/callers db :app.kw/thing)) "a key's functions, one AVET seek")
    (is (= [src] (mapv :src (program/schema-dependents db #{:app.kw/count}))) "the form that re-arms when a key it names changes")
    (is (empty? (:keywords (eval-account 'app.pt-kw "(defn plain [m] (:x m))"))) "no qualified keyword, nothing recorded")))

(deftest catches-and-clocks-are-recorded
  ;; for the guard detectors (session PATTERNS): a hiding guard is a catch answering a constant;
  ;; time deciding is a sleep, a deref with a timeout, or core.async's timeout
  (let [a (eval-account 'app.pt-guard (str "(defn guarded [f p] (Thread/sleep 1) (deref p 10 :late)"
                                           " (try (f) (catch IllegalStateException e nil) (catch Exception e (throw e)) (catch Throwable t (str t))))"))]
    (is (= #{{:class "IllegalStateException" :answers :constant} {:class "Exception" :answers :rethrows} {:class "*" :answers :expr}}
           (set (map read-string (:catches a)))) "each clause: its class, and what its handler answers")
    (is (contains? (:refs a) 'Thread/sleep) "a host static is a reference")
    (is (= '#{clojure.core/deref} (:timeouts a)) "a deref with a timeout")
    (is (empty? (:timeouts (eval-account 'app.pt-guard "(defn plain [p] @p)"))) "a plain deref is no clock")))

(deftest a-redefinition-supersedes-the-form-it-replaces
  (let [db (record (empty-db) 'app.pt-re "(defn f [x] x)" 'app.pt-re "(defn g [x] (f x))")
        order-f (get-in (var-row db 'app.pt-re/f) [:var/form :form/order])
        db2 (record db 'app.pt-re "(defn f [x] (inc x))")]
    (is (= ["(defn f [x] (inc x))" "(defn g [x] (f x))"] (map :src (program/load-order (in-ns-forms db2 'app.pt-re))))
        "the new form in the old one's place, before g")
    (is (= order-f (get-in (var-row db2 'app.pt-re/f) [:var/form :form/order])))
    (is (= 2 (count (in-ns-forms db2 'app.pt-re))) "the replaced form is retracted")
    (testing "a form that also defines something not redefined stays, the new one after it"
      (let [db (record (empty-db) 'app.pt-re2 "(do (def a 1) (def b 2))" 'app.pt-re2 "(def a 3)")]
        (is (= ["(do (def a 1) (def b 2))" "(def a 3)"] (map :src (program/load-order (in-ns-forms db 'app.pt-re2)))))))))

(deftest the-program-digest-moves-with-the-program-and-not-with-data
  (let [db (record (empty-db) 'app.pt-pd "(defn f [x] x)")
        dg (program/program-digest db)]
    (is (= dg (program/program-digest (d/db-with db [{:seon.proof.run/id "main@1" :seon.proof.run/branch "main"}])))
        "a write of data (a run's fact) leaves it")
    (is (not= dg (program/program-digest (record db 'app.pt-pd "(defn f [x] (inc x))"))) "a replaced form moves it")
    (is (not= dg (program/program-digest (record db 'app.pt-pd "(defn g [x] x)"))) "an added form moves it")
    (is (not= dg (program/program-digest (d/db-with db [{:schema/key :app.pt-pd/s :schema/form ":int"}]))) "a schema entry moves it")
    (is (not= dg (program/program-digest (d/db-with db [{:ns/name 'app.pt-pd :ns/tail "(def tail 1)"}]))) "a namespace's tail moves it")))

(deftest load-order-follows-the-real-references
  (testing "a helper defined after its caller loads first (T5b's sibling: g replaced the stub in place, h3 came after)"
    (let [db (record (empty-db) 'app.pt-s "(defn g [x] 0)" 'app.pt-s "(defn other [x] x)"
                     'app.pt-s "(defn h3 [x] (* 3 x))" 'app.pt-s "(defn g [x] (h3 x))")]
      (is (= ["(defn h3 [x] (* 3 x))" "(defn g [x] (h3 x))" "(defn other [x] x)"] (map :src (program/load-order (in-ns-forms db 'app.pt-s)))))))
  (testing "a file's order is kept: a declare interns what a later form defines"
    (let [db (record (empty-db) 'app.pt-d "(declare b)" 'app.pt-d "(defn a [] (b))" 'app.pt-d "(defn b [] 1)")]
      (is (= ["(declare b)" "(defn a [] (b))" "(defn b [] 1)"] (map :src (program/load-order (in-ns-forms db 'app.pt-d))))))))

(def ^:private calc-srcs
  ['user "(ns app.pt-calc)"
   'app.pt-calc "(defn add [a b] (+ a b))"
   'app.pt-calc "(defn sum3 [a b c] (add a (add b c)))"
   'app.pt-calc "(defn neg [x] (- x))"
   'user "(ns app.pt-calc-test (:require [clojure.test :refer [deftest is]] [app.pt-calc :refer [add]]))"
   'app.pt-calc-test "(deftest add-test (is (= 3 (add 1 2))))"
   'app.pt-calc-test "(deftest sum3-test (is (= 6 (app.pt-calc/sum3 1 2 3))))"])

(deftest main-loads-the-live-forms-in-dependency-order
  (let [db (apply record (empty-db) calc-srcs)
        srcs (map :src (program/load-order (program/live-forms db)))]
    (is (< (.indexOf srcs "(ns app.pt-calc)") (.indexOf srcs "(defn add [a b] (+ a b))")))
    (is (< (.indexOf srcs "(defn sum3 [a b c] (add a (add b c)))") (.indexOf srcs "(deftest add-test (is (= 3 (add 1 2))))"))
        "a namespace after the namespaces its forms require")
    (remove-ns 'app.pt-calc) (remove-ns 'app.pt-calc-test)
    (is (= [] (:errors (program/load-all! db))) "it loads from nothing: the test namespace's :refer finds add")
    (is (= {:test 2 :pass 2 :fail 0 :error 0}
           (select-keys (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.pt-calc-test)) [:test :pass :fail :error])))))

(defn- selected
  "What a change from db to db' selects, as every run selects it: seon.proofs/plan's :selected
  over the two dbs' reach digests."
  [db db']
  (let [in {:env "e" :tree "t"}]
    (set (:selected (proofs/plan (program/reach-digests db in) (program/reach-digests db' in) {} #{})))))

(deftest a-test-reaches-what-it-calls
  (let [db (apply record (empty-db) calc-srcs)]
    (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test} (selected db (record db 'app.pt-calc "(defn add [a b] (+ b a))")))
        "add-test calls add through its :refer; sum3-test reaches add through sum3")
    (is (= '#{} (selected db (record db 'app.pt-calc "(defn neg [x] (- 0 x))"))) "no test reaches neg")))

(deftest a-file-analyzed-gives-the-repl-facts
  (let [text "(ns app.pt-file\n  (:require [clojure.string :as s]))\n\n;; doubles\n(defn twice [x] (* 2 x))\n\n(comment (twice 2))\n(defmulti m :k)\n(defmethod m :a [_] (s/join [\"a\"]))\n"
        {:keys [forms tail]} (source/read-forms 'user text)]
    (is (= text (str (apply str (mapcat (juxt :lead :src) forms)) tail)) "lead + text of every form + tail is the file, byte for byte")
    (is (= "\n\n;; doubles\n" (:lead (second forms))) "the comment before a form is its lead")
    (let [{:keys [accounts]} (source/accounts 'user text)
          by-src (into {} (map (juxt :src identity)) accounts)]
      (is (= 5 (count accounts)) "every form, the comment block a text form")
      (is (= '[{:var app.pt-file/twice :kind :fn}] (:defs (by-src "(defn twice [x] (* 2 x))"))))
      (is (= '[{:multi app.pt-file/m :dispatch ":a"}] (:methods (by-src "(defmethod m :a [_] (s/join [\"a\"]))"))))
      (is (= '#{clojure.string/join clojure.core/defmethod-setup clojure.core/defmethod} (:refs (by-src "(defmethod m :a [_] (s/join [\"a\"]))"))))
      (is (= (select-keys (eval-account 'app.pt-file "(defn twice [x] (* 2 x))") [:id :defs :refs])
             (select-keys (by-src "(defn twice [x] (* 2 x))") [:id :defs :refs]))
          "the REPL's facts for the same text"))))

(deftest defmethod-extend-and-defrecord-merge-and-load-on-main
  (let [base (empty-db)
        branch (record base
                       'user "(ns app.pt-mm)"
                       'app.pt-mm "(defmulti area :shape)"
                       'app.pt-mm "(defmethod area :square [{:keys [s]}] (* s s))"
                       'app.pt-mm "(defprotocol Named (label [x]))"
                       'app.pt-mm "(extend-type String Named (label [x] (str \"s:\" x)))"
                       'app.pt-mm "(defrecord Circle [r] Named (label [_] (str \"circle \" r)))"
                       'app.pt-mm "(defmethod area :circle [{:keys [r]}] (* 3 r r))")
        r (pm/three-way (pm/program base #{}) (pm/program branch #{}) (pm/program base #{}))
        main (d/db-with base (pm/merge-tx base base branch (keys (:ours r)) #{}))]
    (is (= '#{[:ns app.pt-mm] [:var app.pt-mm/area] [:method app.pt-mm/area ":square"] [:method app.pt-mm/area ":circle"]
              [:var app.pt-mm/Named] [:var app.pt-mm/label] [:impl app.pt-mm/Named "String"]
              [:var app.pt-mm/Circle] [:var app.pt-mm/->Circle] [:var app.pt-mm/map->Circle]}
           (set (remove #(= :form (first %)) (keys (:ours r)))))
        "each definition is its own merge key: methods [multi dispatch], extensions [protocol type]")
    (is (= [] (pm/unmerged main)))
    (remove-ns 'app.pt-mm)
    (is (= [] (:errors (program/load-all! main))) "main loads the merged forms from nothing")
    (is (= [16 27] [((resolve 'app.pt-mm/area) {:shape :square :s 4}) ((resolve 'app.pt-mm/area) {:shape :circle :r 3})]))
    (is (= ["s:x" "circle 2"] [((resolve 'app.pt-mm/label) "x") ((resolve 'app.pt-mm/label) ((resolve 'app.pt-mm/->Circle) 2))]))))

(deftest a-program-calling-alter-var-root-is-refused-naming-it
  (let [base (empty-db)
        branch (record base 'app.pt-mut "(defn fine [m] (assoc m :intern 1))"
                       'app.pt-mut "(defn sneaky [] (alter-var-root #'app.pt-mut/fine (constantly 3)))")
        r (pm/three-way (pm/program base #{}) (pm/program branch #{}) (pm/program base #{}))]
    (is (= [{:ns 'app.pt-mut :defines '[[:var app.pt-mut/sneaky]] :call 'clojure.core/alter-var-root}]
           (mapv #(dissoc % :form) (pm/mutations base (:ours r))))
        "one finding: the definition and the call, from the compiler's resolved reference")
    (testing "a namespace imported from the platform's source is not judged"
      (let [main (d/db-with base [{:ns/name 'app.pt-mut :ns/file "src/app/pt_mut.clj"}])]
        (is (= [] (pm/mutations main (:ours r))))))))

(deftest a-def-inside-a-fn-body-is-refused
  (let [base (empty-db)
        branch (record base 'app.pt-inl "(defn setup! [] (def config {:a 1}))")
        r (pm/three-way (pm/program base #{}) (pm/program branch #{}) (pm/program base #{}))]
    (is (= [{:ns 'app.pt-inl :defines '[[:var app.pt-inl/setup!]] :inline-def 'app.pt-inl/config}]
           (mapv #(dissoc % :form) (pm/mutations base (:ours r)))))
    (is (empty? (pm/mutations base (:ours (pm/three-way (pm/program base #{})
                                                        (pm/program (record base 'app.pt-inl "(when true (def top-level-ok 1))") #{})
                                                        (pm/program base #{})))))
        "a definition nested anywhere at a form's top is fine")))

;; ---- tests run by reach (task seon.program/tests-run-by-reach) ---------------------------

(def ^:private calc-tests '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test app.pt-calc-test/neg-test})

(defn- calc-db []
  (apply record (empty-db) (concat calc-srcs ['app.pt-calc-test "(deftest neg-test (is (= -1 (app.pt-calc/neg 1))))"])))

(defn- scratch-dir [label]
  (let [d (str (System/getProperty "user.dir") "/target/tmp/reach-" label "-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(deftest a-changed-function-changes-the-digests-of-exactly-the-tests-reaching-it
  (let [db (calc-db)
        before (select-keys (program/reach-digests db {:env "e" :tree "t"}) calc-tests)
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        after (select-keys (program/reach-digests db' {:env "e" :tree "t"}) calc-tests)]
    (is (= calc-tests (set (keys before))) "every test has a digest")
    (is (= 3 (count (set (vals before)))) "each test its own")
    (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test}
           (set (for [[t d] after :when (not= d (before t))] t)))
        "add changed: exactly the tests reaching it moved (sum3-test through sum3), neg-test kept its digest")
    (is (not= before (select-keys (program/reach-digests db {:env "e2" :tree "t"}) calc-tests))
        "the inputs without program facts are in every digest")))

(deftest an-update-gives-the-digests-a-rebuild-gives
  (let [db (calc-db)
        in {:env "e" :tree "t"}
        g (program/reach-graph db nil)
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        g' (program/reach-update g db')
        db'' (record db' 'app.pt-calc "(defn neg [x] (- 0 x))")]
    (is (= (program/reach-index db' in) (program/graph-index g' in nil)) "the update and the rebuild agree")
    (is (not= (program/graph-index g in nil) (program/graph-index g' in nil)) "and the change moved them")
    (is (< (:affected g') (count (:rows g'))) "only add's component and what reaches it were re-folded")
    (is (= (program/reach-index db'' in) (program/graph-index (program/reach-update g db'') in nil))
        "two changes since the base, from the base's graph")
    (is (= (program/graph-index g in nil) (program/graph-index (program/reach-update g db) in nil)) "no change: the same")
    (is (= (program/reach-index db in) (program/graph-index (program/reach-update (program/reach-update g db'') db) in nil))
        "and back: a later value's graph updated to an older one (a resumed branch's base) is the older one's")))

(deftest a-second-run-with-nothing-changed-runs-nothing
  (let [db (calc-db)
        dir (scratch-dir "results")
        digests (select-keys (program/reach-digests db {:env "e" :tree "t"}) calc-tests)
        plan #(proofs/plan nil digests (proofs/greens dir (vec (vals digests))) #{})
        first-plan (plan)
        ran (doto (proofs/run-tests! (:run first-plan) digests (fn [_])) (->> (proofs/store-greens! #(fx/write-file {:req %}) dir)))
        second-plan (plan)]
    (is (= calc-tests (set (:run first-plan))) "nothing recorded: every test runs")
    (is (= {:pass 1 :fail 0 :error 0} (select-keys (ran 'app.pt-calc-test/add-test) [:pass :fail :error])))
    (is (= [] (:run second-plan)) "the second run runs nothing")
    (is (= calc-tests (set (keys (:reused second-plan)))) "and reports every result reused")
    (is (= (digests 'app.pt-calc-test/neg-test) (:digest ((:reused second-plan) 'app.pt-calc-test/neg-test))))))

(defn- run-plan "bin/test's plan of digests against the results in dir." [digests dir]
  (proofs/plan nil digests (proofs/greens dir (vec (vals digests))) #{}))

(defn- checkout!
  "A git checkout at a new directory holding files ({relative path text}), all added."
  [label files]
  (let [d (scratch-dir label)]
    (doseq [[p t] files] (.mkdirs (.getParentFile (java.io.File. (str d "/" p)))) (spit (str d "/" p) t))
    (sh/sh "git" "-C" d "init" "-q")
    (sh/sh "git" "-C" d "add" ".")
    d))

(deftest a-result-recorded-in-one-checkout-is-reused-in-another
  (let [files {"deps.edn" "{}" "resources/seon/schemas/x.edn" "{:x :int}" "src/app/a.clj" "(ns app.a)" "docs/a.md" "# a"}
        a (checkout! "a" files)
        b (checkout! "b" files)
        dir (scratch-dir "shared")
        db (calc-db)
        in-a (fx/inputs a)
        da (select-keys (program/reach-digests db in-a) calc-tests)]
    (is (= in-a (fx/inputs b)) "the same content at another path: the same inputs")
    (proofs/store-greens! #(fx/write-file {:req %}) dir (proofs/run-tests! (:run (run-plan da dir)) da (fn [_])))
    (is (= [] (:run (run-plan (select-keys (program/reach-digests db (fx/inputs b)) calc-tests) dir)))
        "checkout b reuses every result checkout a recorded")
    (spit (str b "/docs/a.md") "# a, edited")
    (is (= (:env in-a) (:env (fx/inputs b))) "prose is no input of a test that reads no files")
    (spit (str b "/resources/seon/schemas/x.edn") "{:x :string}")
    (is (= calc-tests (set (:run (run-plan (select-keys (program/reach-digests db (fx/inputs b)) calc-tests) dir))))
        "a changed schema resource is an input of every test")))

(deftest an-uncommitted-edit-in-a-submodule-is-an-input
  ;; pure-tests 2026-10-23: tree-id stages a submodule at its HEAD, while bin/build copies and
  ;; the test runtime loads its files as they are: a dependency edit not yet committed left
  ;; every digest as it was, so a green proven on the old files was reused on the new
  (let [p (checkout! "with-sub" {"deps.edn" "{}"})
        s (str p "/reference-code/lib")
        core (str s "/core.clj")]
    (.mkdirs (java.io.File. s))
    (spit core "(ns lib.core)")
    (doseq [args [["init" "-q"] ["add" "."] ["-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "lib"]]]
      (apply sh/sh "git" "-C" s args))
    (sh/sh "git" "-C" p "add" "reference-code/lib")
    (let [before (:env (fx/inputs p))]
      (spit core "(ns lib.core) (def edited 1)")
      (is (not= before (:env (fx/inputs p))) "an edit in the submodule's files moves every test's :env")
      (spit core "(ns lib.core)")
      (is (= before (:env (fx/inputs p))) "its files back as committed: the same input as before"))))

;; ---- refs the expansion and the quote leave (task seon.program/refs-name-quoted-symbols-and-macro-uses)

(defn- refs-of [db sym]
  (set (d/q '[:find [?r ...] :in $ ?s :where [?v :var/name ?s] [?v :var/form ?f] [?f :form/refs ?r]] db sym)))

(deftest a-quoted-or-syntax-quoted-var-is-a-ref
  (let [db (record (empty-db) 'user "(ns app.pt-q)" 'app.pt-q "(defn g [] 1)"
                   'app.pt-q "(defn f [] [`g 'app.pt-q/g {:render 'app.pt-q/g}])"
                   'app.pt-q "(defn k [] ['no.such/var 'g '(a b)])")]
    (is (contains? (refs-of db 'app.pt-q/f) 'app.pt-q/g) "a syntax-quoted and a quoted qualified symbol naming a var, in a map too")
    (is (not-any? #{'no.such/var 'g 'a 'b} (refs-of db 'app.pt-q/k)) "a symbol naming no var, or unqualified, is data")))

(deftest a-macro-use-is-a-ref
  (let [db (record (empty-db) 'user "(ns app.pt-qm)" 'app.pt-qm "(defmacro m [x] x)"
                   'app.pt-qm "(defn u [] (m 1))"
                   'app.pt-qm "(defmacro mdo [] `(do (defn ~'w [] 1) (defn ~'w2 [] 2)))"
                   'app.pt-qm "(mdo)"
                   'app.pt-qm "(defmacro m2 [x] `(m ~x))")]
    (is (contains? (refs-of db 'app.pt-qm/u) 'app.pt-qm/m) "the macro a form expanded")
    (is (contains? (refs-of db 'app.pt-qm/w) 'app.pt-qm/mdo) "a top-level macro call that expands to a do: each unit's form names it")
    (is (contains? (refs-of db 'app.pt-qm/m2) 'app.pt-qm/m) "a macro whose template names another: the expansion chain is closed")))

(deftest a-test-reaching-a-quoted-function-changes-digest-with-it
  (let [db (record (empty-db) 'user "(ns app.pt-qr)" 'app.pt-qr "(defn g [] 1)"
                   'user "(ns app.pt-qr-test (:require [clojure.test :refer [deftest is]]))"
                   'app.pt-qr-test "(deftest q-test (is (= 1 ((requiring-resolve 'app.pt-qr/g)))))"
                   'app.pt-qr-test "(deftest other-test (is (= 1 1)))")
        ts '#{app.pt-qr-test/q-test app.pt-qr-test/other-test}
        before (select-keys (program/reach-digests db {:env "e" :tree "t"}) ts)
        db' (record db 'app.pt-qr "(defn g [] 2)")
        after (select-keys (program/reach-digests db' {:env "e" :tree "t"}) ts)]
    (is (not= (before 'app.pt-qr-test/q-test) (after 'app.pt-qr-test/q-test)) "g changed: the test resolving it by name moved")
    (is (= (before 'app.pt-qr-test/other-test) (after 'app.pt-qr-test/other-test)) "the test not reaching g kept its digest")
    (is (= '#{app.pt-qr-test/q-test} (selected db db')) "and every run's selection finds it")))

(deftest a-changed-macro-moves-the-digest-of-each-test-that-expands-it
  (let [db (record (empty-db) 'user "(ns app.pt-mac)" 'app.pt-mac "(defmacro m [x] `(inc ~x))"
                   'user "(ns app.pt-mac-test (:require [clojure.test :refer [deftest is]] [app.pt-mac :as a]))"
                   'app.pt-mac-test "(deftest m-test (is (= 2 (a/m 1))))"
                   'app.pt-mac-test "(deftest plain-test (is (= 1 1)))")
        ts '#{app.pt-mac-test/m-test app.pt-mac-test/plain-test}
        before (select-keys (program/reach-digests db {:env "e" :tree "t"}) ts)
        db' (record db 'app.pt-mac "(defmacro m [x] `(+ 2 ~x))")
        after (select-keys (program/reach-digests db' {:env "e" :tree "t"}) ts)]
    (is (not= (before 'app.pt-mac-test/m-test) (after 'app.pt-mac-test/m-test)) "the macro's body changed: the test expanding it moved (no stale green)")
    (is (= (before 'app.pt-mac-test/plain-test) (after 'app.pt-mac-test/plain-test)) "a test not expanding it kept its digest")
    (is (= '#{app.pt-mac-test/m-test} (selected db db')) "and every run's selection finds exactly it")))

(deftest a-changed-contract-schema-moves-the-digest-of-tests-reaching-the-contracted-var
  (let [schema (fn [db k form] (d/db-with db [{:schema/key k :schema/form form}]))
        db (-> (record (empty-db) 'user "(ns app.pt-sc)"
                       'app.pt-sc "(defn h {:malli/schema [:=> [:cat :app.pt-sc/n] :app.pt-sc/n]} [x] x)"
                       'user "(ns app.pt-sc-test (:require [clojure.test :refer [deftest is]] [app.pt-sc :as a]))"
                       'app.pt-sc-test "(deftest h-test (is (= 1 (a/h 1))))"
                       'app.pt-sc-test "(deftest plain-test (is (= 1 1)))")
               (schema :app.pt-sc/n "[:and :int :app.pt-sc/base]")
               (schema :app.pt-sc/base "int?"))
        ts '#{app.pt-sc-test/h-test app.pt-sc-test/plain-test}
        dg (fn [db] (select-keys (program/reach-digests db {:env "e" :tree "t"}) ts))
        before (dg db)
        after (dg (schema db :app.pt-sc/n "[:and :string :app.pt-sc/base]"))
        after-base (dg (schema db :app.pt-sc/base "pos-int?"))]
    (is (not= (before 'app.pt-sc-test/h-test) (after 'app.pt-sc-test/h-test)) "the schema h's contract names changed: the test calling h moved")
    (is (not= (before 'app.pt-sc-test/h-test) (after-base 'app.pt-sc-test/h-test)) "and a schema that schema names, on")
    (is (= (before 'app.pt-sc-test/plain-test) (after 'app.pt-sc-test/plain-test) (after-base 'app.pt-sc-test/plain-test))
        "a test not reaching h kept its digest")))

(deftest divergence-names-each-change-a-merge-carries
  ;; owner D4 (2026-10-22): the agent sees, every turn, every change its branch's merge will
  ;; carry back to main: what it added, redefined and removed, and the schemas it registered
  (let [base (d/db-with (ts/test-db)
                        [{:db/id "a" :var/name 'app/a :var/form "fa"} {:db/id "fa" :form/id "a1" :form/src "(def a 1)"}
                         {:db/id "b" :var/name 'app/b :var/form "fb"} {:db/id "fb" :form/id "b1" :form/src "(def b 1)"}
                         {:db/id "k" :schema/key :app/k :schema/form ":int"}])
        e (fn [db sym] (d/q '[:find ?e . :in $ ?n :where [?e :var/name ?n]] db sym))
        b-form (:db/id (:var/form (d/entity base (e base 'app/b))))
        branch (d/db-with base [{:db/id (e base 'app/a) :var/form "fa2"} {:db/id "fa2" :form/id "a2" :form/src "(def a 2)"}
                                [:db/retract (e base 'app/b) :var/form b-form]
                                {:db/id "c" :var/name 'app/c :var/form "fc"} {:db/id "fc" :form/id "c1" :form/src "(def c 1)"}
                                {:schema/key :app/k :schema/form ":string"} {:schema/key :app/n :schema/form ":int"}])]
    (is (= {:added ['app/c] :changed ['app/a] :removed ['app/b] :schemas [:app/k :app/n]} (program/divergence base branch)))
    (is (= #{'app/a 'app/b 'app/c} (program/changed-vars base branch)) "the changed vars are its vars")
    (is (= {} (program/divergence base base)) "an unchanged branch carries nothing")))

(deftest a-branch-that-merged-main-carries-only-its-own-changes
  ;; root, 2026-10-22: entity ids are a branch's own, and a merge applies main's forms by
  ;; content under the branch's ids; against its merge base (main's head once merged), the
  ;; branch carries only what it changed itself, never main's changes
  (let [base (d/db-with (ts/test-db) [{:db/id "f" :var/name 'app/f :var/form "ff"} {:db/id "ff" :form/id "(def f 1)" :form/src "(def f 1)"}
                                      {:schema/key :app/k :schema/form ":int"}])
        e (fn [db sym] (d/q '[:find ?e . :in $ ?n :where [?e :var/name ?n]] db sym))
        ;; main redefines f, adds g, changes a schema
        main (d/db-with base [{:db/id (e base 'app/f) :var/form "f2"} {:db/id "f2" :form/id "(def f 2)" :form/src "(def f 2)"}
                              {:db/id "g" :var/name 'app/g :var/form "g1"} {:db/id "g1" :form/id "(def g 1)" :form/src "(def g 1)"}
                              {:schema/key :app/k :schema/form ":string"}])
        ;; the branch first writes entities of its own; identities still take their content ids
        own (d/db-with base (vec (for [i (range 5)] {:db/id (str "x" i) :form/id (str "(comment " i ")") :form/src "x"})))
        ;; it adds h, then merges main: main's forms by content, at the same content ids
        merged (d/db-with own [{:db/id "h" :var/name 'app/h :var/form "h1"} {:db/id "h1" :form/id "(def h 1)" :form/src "(def h 1)"}
                               {:db/id (e own 'app/f) :var/form "bf2"} {:db/id "bf2" :form/id "(def f 2)" :form/src "(def f 2)"}
                               {:db/id "bg" :var/name 'app/g :var/form "bg1"} {:db/id "bg1" :form/id "(def g 1)" :form/src "(def g 1)"}
                               {:schema/key :app/k :schema/form ":string"}])
        form-e (fn [db v] (:db/id (:var/form (d/entity db (e db v)))))]
    (is (= (form-e main 'app/f) (form-e merged 'app/f)) "f's form is one entity on both sides")
    (is (= (e main 'app/g) (e merged 'app/g)) "and so is g")
    (is (= {:added ['app/h]} (program/divergence main merged)) "only h, the branch's own")
    (is (= #{'app/h} (program/changed-vars main merged)) "and the tests it reaches are h's")))

;; ---- digests are the tree's, never the process's (root 2026-10-22: every bin/test --reach run reused nothing)

(deftest the-same-tree-gives-the-same-digests-in-any-checkout-and-any-run
  ;; a quoted name counted as a ref only when its var existed yet (seon.program/quoted-vars), so
  ;; the digests followed what the process had analyzed before: 43 of 387 equal on a second
  ;; import of the same tree. The same commit, imported twice and from a copy at another path,
  ;; gives the same digest for every test: a second run reruns nothing, two worktrees share
  (let [here (System/getProperty "user.dir")
        copy (scratch-dir "copy")
        ;; src and test as they are on disk (tracked and untracked, unignored), not HEAD's: the
        ;; digests are the working tree's, and HEAD's copy was red whenever the tree was dirty
        ;; (pure-tests 2026-10-23: HEAD is no input of the digest)
        ;; (a tracked file deleted on disk is no file of the tree: left out)
        _ (sh/sh "zsh" "-c" (str "cd '" here "' && for f in ${(0)\"$(git ls-files -z -co --exclude-standard src test)\"}; do [[ -e $f ]] && print -rN -- $f; done | tar -c --null -T - -f - | tar -x -C '" copy "'"))
        in {:env "e" :tree "t"}
        a (program/reach-digests (runner/program-db here (ts/program-store)) in)
        b (program/reach-digests (runner/program-db here (ts/program-store)) in)
        c (program/reach-digests (runner/program-db copy (ts/program-store)) in)]
    (is (< 300 (count a)) "the whole suite")
    (is (= a b) "the same tree imported twice in one process: every digest equal")
    (is (= a c) "the same tree in another checkout: every digest equal, so its results are shared")
    (is (= [] (:run (run-plan a (let [dir (scratch-dir "same-tree")]
                                  (proofs/store-greens! #(fx/write-file {:req %}) dir (into {} (for [[t dg] a] [t {:test t :digest dg :pass 1 :fail 0 :error 0}])))
                                  dir))))
        "with each green stored, a second run on the unchanged tree runs nothing")))

(deftest only-a-test-reading-the-tree-takes-its-digest
  ;; seon.program/env-readers: platform reads of resources/ (the baked schemas every contract
  ;; expands), deps.edn or web/ are :env inputs already, so reaching them does not make a test
  ;; rerun on every edit (327 of 368 did); a reader of src, test, docs or the task files still does
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        a (program/reach-digests db {:env "e" :tree "t1"})
        b (program/reach-digests db {:env "e" :tree "t2"})
        moved (set (for [[t d] a :when (not= d (b t))] t))]
    (is (not (moved 'seon.reader-test/prose-without-a-fence-is-a-comment)) "a reader test reaches only :env reads: the tree is no input")
    (is (moved 'seon.task-files-test/the-shipped-task-files-load) "a test reading the task files (my.root/task-files, not in :env): the tree is its input")
    (is (moved 'seon.program-test/a-result-recorded-in-one-checkout-is-reused-in-another) "a test reading files itself (test-fx/inputs): the tree is its input")
    (is (< (count moved) (quot (count a) 2)) (str (count moved) " of " (count a) " tests take the tree: fewer than half"))))

(deftest a-test-naming-another-test-does-not-reach-it
  ;; seon.agent-test's tests quote the proofs they ask for: data, never a call, so a change the
  ;; named test reaches selects it alone (with quoted names by namespace, the naming tests
  ;; selected themselves and booted roots in the agent's test fork)
  (let [db (record (calc-db) 'user "(ns app.pt-names-test (:require [clojure.test :refer [deftest is]]))"
                   'app.pt-names-test "(deftest names-a-proof (is (= 'app.pt-calc-test/add-test 'app.pt-calc-test/add-test)))")
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        k 'app.pt-names-test/names-a-proof
        before (program/reach-digests db {:env "e" :tree "t"})
        after (program/reach-digests db' {:env "e" :tree "t"})]
    (is (not= (before 'app.pt-calc-test/add-test) (after 'app.pt-calc-test/add-test)) "the named test reaches add: it moves")
    (is (= (before k) (after k)) "the test only naming it keeps its digest")
    (is (not (contains? (selected db db') k)) "and every run's selection leaves it out")))

;; ---- inputs the reach digest must hold (review 2026-10-22: each let a stale green be reused)

(deftest a-proc-named-only-in-system-edn-is-reached
  ;; root's procs are named in resources/seon/system.edn and resolved by name at boot, so no
  ;; code refers to them: a test booting root reaches them only through the declaration's reader
  (let [db (record (empty-db) 'user "(ns app.pt-sys)" 'app.pt-sys "(defn read-decl [] :decl)" 'app.pt-sys "(defn start [] (read-decl))"
                   'user "(ns app.pt-procs)" 'app.pt-procs "(defn writer-proc [] 1)"
                   'user "(ns app.pt-sys-test (:require [clojure.test :refer [deftest is]] [app.pt-sys :as s]))"
                   'app.pt-sys-test "(deftest boot-test (is (= :decl (s/start))))")
        db' (record db 'app.pt-procs "(defn writer-proc [] 2)")
        in {:env "e" :tree "t" :data-refs '{app.pt-sys/read-decl #{app.pt-procs/writer-proc}}}
        k 'app.pt-sys-test/boot-test]
    (is (not= ((program/reach-digests db in) k) ((program/reach-digests db' in) k)) "the proc changed: the test booting through the declaration moves")
    (is (contains? (get (fx/declared-refs (str (System/getProperty "user.dir") "/resources/seon/system.edn")) 'seon.system/read-decl) 'seon.writer/writer-proc)
        "the shipped declaration's procs are the reader's data refs")))

(deftest a-test-running-the-binary-takes-the-tree
  ;; seon.root-test runs target/seon (seon.test-store/binary-sh): what it proves is the whole
  ;; program the binary is built from, so any change to the tree moves its digest
  (let [db (runner/program-db (System/getProperty "user.dir") (ts/program-store))
        a (program/reach-digests db {:env "e" :tree "t1"})
        b (program/reach-digests db {:env "e" :tree "t2"})
        runs-binary '[seon.root-test/root-threads-are-what-root-declares seon.root-test/a-root-binary-requires-a-library-it-did-not-compile
                      seon.root-test/a-root-resumes-a-store-an-earlier-build-wrote seon.agent-test/an-agent-holds-mains-tests-and-runs-them]]
    (is (every? #(not= (a %) (b %)) runs-binary) "every test running the binary moves with the tree")
    (is (= (a 'seon.reader-test/prose-without-a-fence-is-a-comment) (b 'seon.reader-test/prose-without-a-fence-is-a-comment)) "a test that runs no binary does not")))

(deftest the-config-in-force-is-an-input
  ;; config/local.edn is gitignored and #env values are no files: a result proven under one
  ;; checkout's config was reused in another's
  (let [here (System/getProperty "user.dir")
        cfg {:seon.config.ai/model "a" :seon.config.root/runs-dir (str here "/runs") :seon.config.web/port 8451}
        env #(:env (fx/inputs here % here))]
    (is (not= (env cfg) (env (assoc cfg :seon.config.ai/model "b"))) "another value in force: another :env, every test reruns")
    (is (= (env cfg) (env (assoc cfg :seon.config.web/port 9999))) "a checkout's own TCP port is no input")
    ;; owner, 2026-10-23: agents' greens and a checkout's are one store, so where a process
    ;; runs (its :seon.config/place keys) is no input; how a test behaves still is
    (is (= (env cfg) (env (assoc cfg :seon.config.root/main "in-agent-1" :seon.config.root/store-path "/x/store"
                                     :seon.config.source/root here :seon.config.source/dirs ["src" "test"]
                                     :seon.config.source/test-results-dir "/x/results")))
        "a root's own main, store, source and results directory are no input")
    (is (not= (env cfg) (env (assoc cfg :seon.config.store/branching-factor 32))) "a bound a test runs under is one")
    (is (= (:env (fx/inputs here cfg here))
           (:env (fx/inputs here (assoc cfg :seon.config.root/runs-dir "/elsewhere/runs") "/elsewhere")))
        "a path under the checkout is the same input in any checkout")))

(deftest an-eval-that-changes-a-contract-or-docstring-says-so
  ;; owner, 2026-10-25 (DECISIONS: a change to a schema is an explicit fact the eval shows): what an
  ;; eval's definitions change of an existing var's contract or docstring, read from its own tx-data,
  ;; with the wiki sections citing the var and the form that reads each
  (let [db0 (ts/test-db)
        db (d/db-with db0 [{:db/id "f" :var/name 'app/f :var/doc "Old words." :var/contract "[:=> [:cat] :int]"}
                           {:db/id "g" :var/name 'app/g :var/doc "Kept."}])
        db (d/db-with db (seon.docs/load-tx db "docs" {"docs/a.md" "# A\n\nIt reads `app/f`.\n"}))
        fe (pdb/eid db :var/name 'app/f)]
    (is (= [{:var 'app/f :was {:var/doc "Old words."} :now {:var/doc "New words."}
             :cited-by [{:section "docs/a.md#a" :read '(my.agent/help "docs/a.md#a")}]}]
           (program/meaning-changes db [{:db/id -1 :var/name 'app/f :var/doc "New words." :var/form -2} {:db/id -2 :form/id "x"}]))
        "a docstring changed: before and after, and who cites it")
    (is (= [:var/contract] (keys (:was (first (program/meaning-changes db [{:var/name 'app/f :var/contract "[:=> [:cat :int] :int]"}]))))) "a contract changed")
    (is (= {:var/doc "Old words."} (:was (first (program/meaning-changes db [[:db/retract fe :var/doc "Old words."]])))) "a docstring removed")
    (is (= {} (:now (first (program/meaning-changes db [[:db/retract fe :var/doc "Old words."]])))))
    (is (nil? (program/meaning-changes db [{:var/name 'app/f :var/doc "Old words."} {:var/name 'app/g :var/form -1}])) "the same docstring, a body alone: nothing")
    (is (nil? (program/meaning-changes db [{:var/name 'app/new :var/doc "Fresh."}])) "a new var changes nothing a section relied on")
    (is (nil? (program/meaning-changes db [])) "an eval that defines nothing")))
