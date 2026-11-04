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
  (:require [seon.test-store :as ts] [my.test :as mt] [clojure.test :refer [deftest is testing]]
            [datahike.api :as d] [clojure.java.shell]
            [seon.db :as pdb]
            [seon.program :as program] [seon.doc] [seon.server]
            [seon.proofs :as proofs]
            [seon.fx :as fx]
            [seon.merge :as pm]
            [seon.source :as source]
            [clojure.java.shell :as sh]
            [test-runner :as runner] [clojure.string :as str] [seon.config :as cfg]
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
    (is (= ['app.pt-kw/kw-user] (seon.doc/callers db :app.kw/thing)) "a key's functions, one AVET seek")
    ;; two keys, the first an attribute: as a vector Datahike read them as a lookup ref
    ;; (datahike.query-test's (= :query/nested-call (:error r)) failed the program db)
    (is (= #{:ns/name :app.kw/err} (set (d/q '[:find [?k ...] :where [?v :var/name app.pt-kw/two] [?v :var/form ?f] [?f :form/keywords ?k]]
                                             (record (empty-db) 'app.pt-kw "(defn two [r] [(:ns/name r) :app.kw/err])"))))
        "an attribute and a key, each a fact")
    (is (= #{:app.kw/init} (:keywords (eval-account 'app.pt-kw "(def kw-init :app.kw/init)"))) "a def's init is its data")
    (is (= #{:app.kw/tested} (:keywords (eval-account 'app.pt-kw "(clojure.test/deftest kw-t (clojure.test/is (= :app.kw/tested (:app.kw/tested {}))))")))
        "a deftest's body (its var's :test) names its data; the metadata's own keys do not")
    (is (= [src] (mapv :src (program/schema-dependents db #{:app.kw/count}))) "the form that re-arms when a key it names changes")
    (is (empty? (:keywords (eval-account 'app.pt-kw "(defn plain [m] (:x m))"))) "no qualified keyword, nothing recorded")))

(deftest a-branch-cut-before-form-keywords-gains-them-on-resume
  ;; root, 2026-10-25 (ctx-atom's finding): a branch cut before :form/keywords killed its agent at
  ;; resume, schema-dependents reading an attribute its schema lacks (now it reads none of its
  ;; datoms, docs/forks.md). A branch keeps the schema it
  ;; was cut with; resumed, it gains this program's attributes (seon.db/install-missing!, as a kept
  ;; store does when a root opens it) and its forms the facts they were recorded without (gained-tx)
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        ;; the schema as it was before :form/keywords, and a contracted form recorded then
        _ (d/transact conn (vec (remove #(= :form/keywords (:db/ident %)) pdb/schema)))
        _ (d/transact conn [{:db/id "n" :ns/name 'app.old} {:db/id "f" :form/id "app.old/f" :form/src "(defn f [m] (:app.old/thing m))" :form/ns "n" :form/order 0 :form/defines #{"v"}}
                            {:db/id "v" :var/name 'app.old/f :var/form "f" :var/kind :fn :var/contract (seon.edn/encode [:=> [:cat [:map [:app.old/thing :int]]] :int])}])]
    (is (= [] (program/schema-dependents @conn #{:app.old/thing}))
        "cut before the attribute: it holds none of its datoms, so nothing depends on it (Datahike 7eea478b; before, the read threw and the agent died)")
    (is (= [] (program/gained-tx @conn)) "the attribute not yet installed: nothing to derive")
    (let [added (pdb/install-missing! conn)
          _ (d/transact conn (program/gained-tx @conn))]
      (is (contains? added :form/keywords) "resumed: the attribute is installed on the branch")
      (is (= #{:app.old/thing} (set (d/q '[:find [?k ...] :where [?f :form/id "app.old/f"] [?f :form/keywords ?k]] @conn)))
          "and the form gains the keys its contract names")
      (is (= ["app.old/f"] (mapv :id (program/schema-dependents @conn #{:app.old/thing})))
          "so a change to that key re-arms the form, as it did before :form/keywords")
      (is (= #{} (pdb/install-missing! conn)) "a second resume installs nothing")
      (is (= [] (program/gained-tx @conn)) "and derives nothing: a form holds keywords now"))))

(deftest a-base-cut-before-an-attribute-has-a-reach-graph
  ;; test-audit, 2026-10-27, on a store kept from before 7177cc5899: a resumed agent's base (its
  ;; merge base with main) was cut before :form/ops, and its :context died reading the base's reach
  ;; graph (Bad entity attribute :form/ops, not defined in current schema). A db that does not
  ;; declare an attribute holds none of its datoms: its forms' rows read it as absent
  ;; (docs/source.md#tests-run-by-reach)
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        _ (d/transact conn (vec (remove #(= :form/ops (:db/ident %)) pdb/schema)))
        _ (d/transact conn [{:db/id "n" :ns/name 'app.old} {:db/id "f" :form/id "app.old/f" :form/src "(defn f [m] m)" :form/ns "n" :form/order 0 :form/defines #{"v"}}
                            {:db/id "v" :var/name 'app.old/f :var/form "f" :var/kind :fn}])
        g (program/reach-graph @conn nil)]
    (is (= #{"app.old/f"} (set (map :id (vals (:rows g))))) "the base's graph holds its form")
    (is (= #{"app.old/f" "app.old/g"}
           (set (keys (:rows (program/reach-update g (d/db-with @conn [{:db/id "f" :form/id "app.old/g" :form/src "(defn g [m] m)" :form/ns [:ns/name 'app.old]
                                                                       :form/order 1 :form/defines #{"v"}} {:db/id "v" :var/name 'app.old/g :var/form "f" :var/kind :fn}]))))))
        "and a form added on it is read too, entity by entity")))

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
    (is (= dg (program/program-digest (d/db-with db [{:seon.message/id "m1" :seon.message/from "root" :seon.message/body "hello"}])))
        "a write of data (a message) leaves it")
    (is (not= dg (program/program-digest (record db 'app.pt-pd "(defn f [x] (inc x))"))) "a replaced form moves it")
    (is (not= dg (program/program-digest (record db 'app.pt-pd "(defn g [x] x)"))) "an added form moves it")
    (is (not= dg (program/program-digest (d/db-with db [{:schema/key :app.pt-pd/s :schema/form ":int"}]))) "a schema entry moves it")
    (is (not= dg (program/program-digest (d/db-with db [{:ns/name 'app.pt-pd :ns/tail "(def tail 1)"}]))) "a namespace's tail moves it")))

(deftest a-runs-digest-moves-with-a-tasks-proofs
  ;; bench-scope-5: a design registered three proofs and its prove answered the run from before
  ;; them, "green: ran 0"; a run selects the task's proofs, so its key moves with them
  (let [db (d/db-with (record (empty-db) 'app.pt-rd "(defn f [x] x)\n(defn g [x] x)") [{:seon.task/id "app.pt-rd/t"}])
        dg (program/run-digest db)]
    (is (= dg (program/run-digest (d/db-with db [{:seon.message/id "m1" :seon.message/from "root" :seon.message/body "hello"}])))
        "a write of other data leaves it")
    (is (not= dg (program/run-digest (record db 'app.pt-rd "(defn f [x] (inc x))"))) "the program moves it")
    (let [proved (d/db-with db [{:seon.task/id "app.pt-rd/t" :seon.task/proofs ['app.pt-rd/f]}])]
      (is (not= dg (program/run-digest proved)) "a registered proof moves it")
      (is (not= (program/run-digest proved) (program/run-digest (d/db-with proved [{:seon.task/id "app.pt-rd/t" :seon.task/proofs ['app.pt-rd/g]}])))
          "and another"))))

(deftest load-order-follows-the-real-references
  (testing "a helper defined after its caller loads first (T5b's sibling: g replaced the stub in place, h3 came after)"
    (let [db (record (empty-db) 'app.pt-s "(defn g [x] 0)" 'app.pt-s "(defn other [x] x)"
                     'app.pt-s "(defn h3 [x] (* 3 x))" 'app.pt-s "(defn g [x] (h3 x))")]
      (is (= ["(defn h3 [x] (* 3 x))" "(defn g [x] (h3 x))" "(defn other [x] x)"] (map :src (program/load-order (in-ns-forms db 'app.pt-s)))))))
  (testing "a file's order is kept: a declare interns what a later form defines"
    (let [db (record (empty-db) 'app.pt-d "(declare b)" 'app.pt-d "(defn a [] (b))" 'app.pt-d "(defn b [] 1)")]
      (is (= ["(declare b)" "(defn a [] (b))" "(defn b [] 1)"] (map :src (program/load-order (in-ns-forms db 'app.pt-d))))))))

;; task seon.source/a-tests-reach-is-the-same-however-the-program-was-analyzed (root's ruling,
;; 2026-10-25): a quoted name is data, recorded by name, never a reference: whether its namespace
;; existed depended on what the process had analyzed, so a refresh (only the changed files) dropped
;; what a whole import kept (240 against 250 tests left out on one tree)
(deftest a-quoted-name-is-data-whatever-the-process-analyzed
  (let [srcs {"src/app/pt_qa.clj" "(ns app.pt-qa)\n(defn f [] (list 'app.pt-qb/g 1))\n(defmacro m [x] `(app.pt-qb/g ~x))\n"
              "src/app/pt_qb.clj" "(ns app.pt-qb)\n(defn g [x] x)\n"}
        refs (fn [db v] (set (:form/refs (:var/form (d/entity db [:var/name v])))))
        whole (let [db (empty-db)] (d/db-with db (source/import-tx db srcs)))
        ;; app.pt-qa alone, in a process that no longer holds app.pt-qb (a refresh's: only the
        ;; changed file is read)
        alone (try (remove-ns 'app.pt-qb)
                   (let [db (empty-db)] (d/db-with db (source/import-tx db (select-keys srcs ["src/app/pt_qa.clj"]))))
                   (finally (remove-ns 'app.pt-qa)))]
    (is (not (contains? (refs whole 'app.pt-qa/f) 'app.pt-qb/g)) "a name in quoted data is no reference of the form")
    (is (contains? (refs whole 'app.pt-qa/m) 'app.pt-qb/g) "a macro's template is its expansion's code: its names are")
    (is (= (refs whole 'app.pt-qa/f) (refs alone 'app.pt-qa/f)) "the same facts whether the named namespace was analyzed or not")
    (is (= (refs whole 'app.pt-qa/m) (refs alone 'app.pt-qa/m)) "for a macro's template too")))

(deftest a-name-a-test-hands-over-quoted-is-what-it-reaches
  ;; root, 2026-10-27: (cost/proof {:of 'seon.program/reach-update ...}) did not reach reach-update,
  ;; so its digest stayed when reach-update changed and a stored green hid a red (17 calls against
  ;; 16). A name quoted in a test's namespace is one the test hands to what runs it by name: a reach
  ;; edge there; quoted in src it stays data (schema-forms' renders, tree-readers)
  (let [srcs {"src/app/pt_hq.clj" "(ns app.pt-hq)\n(defn f [x] x)\n(defn g [x] x)\n(defn run-by-name [sym] (@(requiring-resolve sym) 1))\n(def names ['app.pt-hq/g])\n"
              "test/app/pt_hq_test.clj" "(ns app.pt-hq-test (:require [clojure.test :refer [deftest is]] [app.pt-hq]))\n(deftest by-name (is (= 1 (app.pt-hq/run-by-name 'app.pt-hq/f))))\n(deftest other (is (= 1 (app.pt-hq/run-by-name (first app.pt-hq/names)))))\n"}
        db (let [db (empty-db)] (d/db-with db (source/import-tx db srcs)))
        g (program/reach-graph db nil)]
    (is (some #{'app.pt-hq-test/by-name} (program/tests-reaching g 'app.pt-hq/f)) "the test that quotes f reaches it")
    (is (not (some #{'app.pt-hq-test/other} (program/tests-reaching g 'app.pt-hq/g))) "a name src quotes as data is no edge")
    (let [g0 (program/reach-graph (d/db-with db (source/import-tx db (select-keys srcs ["src/app/pt_hq.clj"]))) nil)
          f2 (record db 'app.pt-hq "(defn f [x] (identity x))")]
      (is (= (program/graph-index (program/reach-graph f2 nil) {:env "e" :tree "t" :config {}} nil)
             (program/graph-index (program/reach-update (program/reach-update g0 db) f2) {:env "e" :tree "t" :config {}} nil))
          "an update agrees with a rebuild when a namespace gains its first test and when a quoted var changes")
      (is (not= ((ts/reach-digests db {:env "e" :tree "t" :config {}}) 'app.pt-hq-test/by-name)
                ((ts/reach-digests f2 {:env "e" :tree "t" :config {}}) 'app.pt-hq-test/by-name))
          "the quoted var changed: the test's digest moved"))
    (is (some #{'seon.program-cost-test/a-change-re-folds-only-what-reaches-it}
              (program/tests-reaching (program/reach-graph (runner/program-db (seon.config/checkout) (ts/kept-program-store)) nil) 'seon.program/reach-update))
        "Seon's own cost proof reaches the function it names")))

(deftest a-namespace-gaining-its-first-test-is-updated-not-rebuilt
  ;; docs/source.md#tests-run-by-reach: an agent that adds a deftest to a namespace src already holds (or writes
  ;; a new test namespace) used to have its graph built from nothing (720 MB, 0.6 s on Seon's program, 114 MB of
  ;; footprint); the forms the namespace already held are now re-edged as added. The rows the update leaves equal a rebuild's.
  (let [srcs {"src/app/pt_ft.clj" "(ns app.pt-ft (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(defn g [x] x)\n(def names ['app.pt-ft/g])\n(defn run-by-name [sym] (@(requiring-resolve sym) 1))\n"}
        db (let [db (empty-db)] (d/db-with db (source/import-tx db srcs)))
        g0 (program/reach-graph db nil)
        in {:env "e" :tree "t" :config {}}
        t2 (record db 'app.pt-ft "(deftest by-name (is (= 1 (run-by-name 'app.pt-ft/f))))")
        t3 (record t2 'app.pt-ft "(defn f [x] (identity x))")
        ;; what differs: the graph-index's answers and the keys of the graph the walk reads
        same? (fn [a b] (vec (concat (when-not (= (program/graph-index a in nil) (program/graph-index b in nil)) [:index])
                                     (remove #(= (% a) (% b)) [:rows :adj :rev :refd :tests :test-nss :plain :defs :methods :impls :contract :reads :scans :proc :bin :own]))))]
    (is (empty? (:test-nss g0)) "no test namespace to begin with")
    (is (= #{'app.pt-ft} (:test-nss (program/reach-update g0 t2))))
    (is (= [] (same? (program/reach-graph t2 nil) (program/reach-update g0 t2))) "gaining its first test: the update is the rebuild")
    (is (some #{'app.pt-ft/by-name} (program/tests-reaching (program/reach-update g0 t2) 'app.pt-ft/f)) "the quoted name is now an edge")
    (is (= [] (same? (program/reach-graph t3 nil) (program/reach-update (program/reach-update g0 t2) t3))) "and a later edit")
    (is (= [] (same? g0 (program/reach-update (program/reach-update g0 t2) db))) "losing its last test: rebuilt, so the same")))

(deftest load-order-reads-no-quoted-name
  ;; task seon.program/load-dependencies-leave-quoted-names-out: a form recorded at a REPL that
  ;; quotes a later var (an error's data naming it) is not ordered after it
  (let [db (record (empty-db) 'app.pt-lq "(defn a [] (ex-info \"no\" {:see 'app.pt-lq/b}))" 'app.pt-lq "(defn b [] (a))")]
    (is (= ["(defn a [] (ex-info \"no\" {:see 'app.pt-lq/b}))" "(defn b [] (a))"]
           (map :src (program/load-order (in-ns-forms db 'app.pt-lq)))))))

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
  (let [in {:env "e" :tree "t" :config {}}]
    (set (:selected (proofs/plan (ts/reach-digests db in) (ts/reach-digests db' in) {} #{})))))

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

(deftest a-file-analyzed-keeps-its-own-docstring
  ;; docs/source.md#another-trees-program-analyzed-beside-this-image: a file read without running (another
  ;; tree's, an old commit's) records the docstring its text holds, not the one the running image's var has
  (eval-account 'app.pt-doc "(defn told \"the image's doc, written later\" [x] x)")
  (let [{:keys [accounts]} (source/accounts 'app.pt-doc "(defn told \"the file's own doc\" [x] x)\n")]
    (is (= "the image's doc, written later" (:doc (meta (find-var 'app.pt-doc/told)))) "the image holds the later definition")
    (is (= [{:var 'app.pt-doc/told :kind :fn :doc "the file's own doc"}] (:defs (first accounts)))
        "the account of the file's form holds the file's docstring")))

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
  (let [d (str (seon.config/checkout) "/target/tmp/reach-" label "-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(defn kept-graph
  "The reach graph of the checkout's own program, as bin/test's plan keeps it (test-runner/reach-graph-of over the kept
  program store): decoded and brought to date, a sixth of what building it costs (docs/source.md#tests-run-by-reach)."
  [here]
  (runner/reach-graph-of (runner/program-db here (ts/kept-program-store)) (:data-refs (fx/inputs here)) (ts/kept-program-store)))

(defn- sampled-repo
  "A new git repository (its path) holding every nth file of the checkout's src, test and schemas as
  they are on disk (tracked and untracked, unignored, not HEAD's: the digests are the working tree's; a
  tracked file deleted on disk is no file) and the files `extra` names: the same cross-file references
  and quoted names as the tree, at 1/n of its import and reach work (what the proofs of a tree's digests
  and pins ask of the whole program cost 3.4 G ticks to import alone). program-db reads a tree from
  its repository's git objects, so the sample is a repository."
  [label n extra]
  (let [here (seon.config/checkout)
        files (->> (:out (apply sh/sh "git" "-C" here "ls-files" "-co" "--exclude-standard" (cfg/value :seon.config.source/dirs)))
                   str/split-lines sort (filter #(.exists (java.io.File. here %)))
                   (keep-indexed (fn [i f] (when (or (zero? (mod i n)) (contains? (set extra) f)) f))))
        listing (str (scratch-dir (str label "-list")) "/files")
        d (scratch-dir label)]
    (spit listing (str/join "\n" files))
    (sh/sh "zsh" "-c" (str "tar -c -C '" here "' -T '" listing "' -f - | tar -x -C '" d "' && git -C '" d "' init -q"))
    d))

(deftest a-changed-function-changes-the-digests-of-exactly-the-tests-reaching-it
  (let [db (calc-db)
        before (select-keys (ts/reach-digests db {:env "e" :tree "t" :config {}}) calc-tests)
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        after (select-keys (ts/reach-digests db' {:env "e" :tree "t" :config {}}) calc-tests)]
    (is (= calc-tests (set (keys before))) "every test has a digest")
    (is (= 3 (count (set (vals before)))) "each test its own")
    (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test}
           (set (for [[t d] after :when (not= d (before t))] t)))
        "add changed: exactly the tests reaching it moved (sum3-test through sum3), neg-test kept its digest")
    (is (not= before (select-keys (ts/reach-digests db {:env "e2" :tree "t" :config {}}) calc-tests))
        "the inputs without program facts are in every digest")))

(deftest a-digest-is-the-content-reached-whatever-recorded-it
  ;; task seon.program/reach-index-reads-each-attribute-once: a form's own digest is its :form/id
  ;; (its content's hash), no longer a hash of the text read back
  (let [in {:env "e" :tree "t" :config {}}
        digests #(select-keys (ts/reach-digests % in) calc-tests)
        db (calc-db)
        moved (record db 'app.pt-calc "(defn add [a b] (+ b a))")]
    (is (= (digests db) (digests (calc-db))) "the same content in another value: the same digests")
    (is (= (digests db) (digests (record moved 'app.pt-calc "(defn add [a b] (+ a b))")))
        "a form changed and changed back: the digests of the content it had")
    (is (= (digests db) (digests (record db 'app.pt-calc "(defn neg [x] (- x))")))
        "a form recorded again as it was moves nothing")
    (let [reach-rows @#'program/reach-rows
          live (set (vals (@#'program/live-ids db)))
          rows (fn [scan?] (into {} (map (juxt :id identity)) (reach-rows db live scan?)))
          want (into {} (for [e live :let [f (program/form-row db e)]] [(:id f) (select-keys f [:id :ns :defines :refs])]))]
      (doseq [scan? [true false]]
        (is (= want (update-vals (rows scan?) #(select-keys % [:id :ns :defines :refs])))
            (str "the walk's rows are the whole rows' id, namespace, definitions and references (scan " scan? ")")))
      (is (every? #(= (:id %) (:own %)) (vals (rows true))) "a form's own digest is its id"))))

(deftest a-stored-reach-gives-the-digests-a-rebuild-gives
  ;; task seon.program/reach-graph-is-stored: a plan reads the graph the last one stored (jfasl in the
  ;; program store) and folds what moved (reach-update); its digests are a rebuild's, and a store
  ;; written under other code or other data-refs is built over
  (let [dir (scratch-dir "stored-reach")
        in {:env "e" :tree "t" :config {}}
        db (calc-db)
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        file (java.io.File. dir "reach-graph.fasl")
        digests (fn [g] (update-vals (program/graph-index g in nil) :digest))
        first-run (runner/reach-graph-of db nil dir)]
    (is (.isFile file) "the first plan stores the graph")
    (is (= (digests (program/reach-graph db nil)) (digests first-run)) "built: a rebuild's digests")
    (let [stamp (.lastModified file)
          second-run (runner/reach-graph-of db' nil dir)]
      (is (= (digests (program/reach-graph db' nil)) (digests second-run)) "stored and updated: a rebuild's digests of the changed program")
      (is (< (:affected second-run) (count (:rows second-run))) "and only what the change reaches was re-folded")
      (is (not= (digests first-run) (digests second-run)) "the change moved some digest")
      (is (= (digests (program/reach-graph db nil)) (digests (runner/reach-graph-of db nil dir)))
          "and back to the first program: its digests again")
      (is (<= stamp (.lastModified file)) "the store moved with the program"))
    (testing "other data-refs: built over"
      (let [refs {'seon.system/read-decl #{'app.pt-calc/add}}]
        (is (= (digests (program/reach-graph db' refs)) (digests (runner/reach-graph-of db' refs dir))))))))

(deftest a-types-method-is-in-the-reach-of-what-constructs-it
  ;; task seon.proofs/a-types-method-moves-the-reach-of-what-uses-it: a change inside a type's method (a method of
  ;; the type's own protocol, of a host interface, of a record, of a protocol extended to a type) moves the digest of a
  ;; test that reaches the function using the type: 2026-11-03, seon.ratom's Reaction changed and a stale green was reused
  (let [in {:env "e" :tree "t" :config {}}
        digest (fn [db t] (get-in (program/graph-index (program/reach-graph db nil) in nil) [t :digest]))]
    (doseq [[label nsym before after use]
            [["a protocol's method, by its ctor" 'app.pt-ty1 "(do (defprotocol P1 (m1 [_])) (deftype T1 [] P1 (m1 [_] 1)))" "(do (defprotocol P1 (m1 [_])) (deftype T1 [] P1 (m1 [_] 2)))" "(defn f [] (T1.))"]
             ["a host interface's method, by its ctor" 'app.pt-ty2 "(deftype T2 [] clojure.lang.IDeref (deref [_] 1))" "(deftype T2 [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (T2.))"]
             ["a type's positional ctor" 'app.pt-ty3 "(deftype T3 [] clojure.lang.IDeref (deref [_] 1))" "(deftype T3 [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (->T3))"]
             ["a record's method, by its ctor" 'app.pt-ty4 "(defrecord R4 [] clojure.lang.IDeref (deref [_] 1))" "(defrecord R4 [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (R4.))"]
             ["a record's map ctor" 'app.pt-ty5 "(defrecord R5 [] clojure.lang.IDeref (deref [_] 1))" "(defrecord R5 [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (map->R5 {}))"]
             ["a protocol extended to a type, by its method" 'app.pt-ty6 "(do (defprotocol P6 (m6 [_])) (extend-type String P6 (m6 [_] 1)))" "(do (defprotocol P6 (m6 [_])) (extend-type String P6 (m6 [_] 2)))" "(defn f [] (m6 \"x\"))"]
             ["a reify's method, in the form that holds it" 'app.pt-ty7 "(defn g [] (reify clojure.lang.IDeref (deref [_] 1)))" "(defn g [] (reify clojure.lang.IDeref (deref [_] 2)))" "(defn f [] (g))"]]]
      (let [tns (symbol (str nsym "-test"))
            t (symbol (str tns) "f-test")
            test-src (str "(deftest f-test (is (some? (" nsym "/f))))")
            db (record (empty-db) nsym before nsym use tns test-src)
            db' (record db nsym after)]
        (is (some? (digest db t)) (str label ": the test has a digest"))
        (is (not= (digest db t) (digest db' t)) (str label ": the method's change moves the test's digest"))))))

(deftest a-types-method-moves-the-digest-on-an-import
  ;; the same, on the path bin/test and root read a program by: analyzed, never run, so the type is in no image and
  ;; its ctor's class is the bare name as written. The answer does not depend on what the process has loaded
  (let [in {:env "e" :tree "t" :config {}}
        digest (fn [db t] (get-in (program/graph-index (program/reach-graph db nil) in nil) [t :digest]))]
    (doseq [[label n before after use]
            [["(T.)" "a" "(deftype TIa [] clojure.lang.IDeref (deref [_] 1))" "(deftype TIa [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (TIa.))"]
             ["(new T)" "b" "(deftype TIb [] clojure.lang.IDeref (deref [_] 1))" "(deftype TIb [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (new TIb))"]
             ["(R.)" "c" "(defrecord RIc [] clojure.lang.IDeref (deref [_] 1))" "(defrecord RIc [] clojure.lang.IDeref (deref [_] 2))" "(defn f [] (RIc.))"]]]
      (let [nsym (str "app.pt-tyi" n) path (str "src/app/pt_tyi" n ".clj") tpath (str "src/app/pt_tyi" n "_test.clj")
            text #(str "(ns " nsym ")\n" % "\n" use "\n")
            test-src (str "(ns " nsym "-test (:require [clojure.test :refer [deftest is]] [" nsym "]))\n(deftest f-test (is (some? (" nsym "/f))))\n")
            t (symbol (str nsym "-test") "f-test")
            db (let [db (empty-db)] (d/db-with db (source/import-tx db {path (text before) tpath test-src})))
            refreshed (d/db-with db (source/refresh-tx db {path (text after)}))
            whole (let [db (empty-db)] (d/db-with db (source/import-tx db {path (text after) tpath test-src})))]
        (is (some? (digest db t)) (str label ": the test has a digest"))
        (is (not= (digest db t) (digest refreshed t)) (str label ": the method's change moves it on a refresh"))
        (is (= (digest refreshed t) (digest whole t)) (str label ": and a whole import agrees"))))))

(deftest an-update-gives-the-digests-a-rebuild-gives
  (let [db (calc-db)
        in {:env "e" :tree "t" :config {}}
        g (program/reach-graph db nil)
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        g' (program/reach-update g db')
        db'' (record db' 'app.pt-calc "(defn neg [x] (- 0 x))")]
    (is (= (ts/reach-index db' in) (program/graph-index g' in nil)) "the update and the rebuild agree")
    (is (not= (program/graph-index g in nil) (program/graph-index g' in nil)) "and the change moved them")
    (is (< (:affected g') (count (:rows g'))) "only add's component and what reaches it were re-folded")
    (is (= (ts/reach-index db'' in) (program/graph-index (program/reach-update g db'') in nil))
        "two changes since the base, from the base's graph")
    (is (= (program/graph-index g in nil) (program/graph-index (program/reach-update g db) in nil)) "no change: the same")
    (is (= (ts/reach-index db in) (program/graph-index (program/reach-update (program/reach-update g db'') db) in nil))
        "and back: a later value's graph updated to an older one (a resumed branch's base) is the older one's")))

(deftest an-index-held-by-the-graph-moves-with-it
  ;; task seon.agent/an-agents-reach-index-is-incremental: the index a graph holds (reach-indexed) after reach-update
  ;; steps is a rebuild's at every step (a redefinition, a test added, a namespace's plain form changed, a
  ;; test removed, back to the first program), and only the tests a step can move are :stale
  (let [in {:env "e" :tree "t" :config {}}
        db0 (calc-db)
        db1 (record db0 'app.pt-calc "(defn add [a b] (+ b a))")
        db2 (record db1 'app.pt-calc-test "(deftest neg2-test (is (= -2 (app.pt-calc/neg 2))))")
        db3 (record db2 'user "(ns app.pt-calc-test (:require [clojure.test :refer [deftest is]] [app.pt-calc :refer [add neg]]))")
        db4 (record db3 'app.pt-calc "(defn neg [x] (- 0 x))")
        rebuilt (fn [db] (ts/reach-index db in))
        held #(program/index-of (program/reach-indexed % in))
        g0 (program/reach-indexed (program/reach-graph db0 nil) in)
        step (fn [g db] (program/reach-update g db))]
    (is (= (rebuilt db0) (program/index-of g0)) "built whole once")
    (let [g1 (step g0 db1)]
      (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test} (:stale g1)) "a redefined add: the tests reaching it only")
      (is (= (rebuilt db1) (held g1)) "and the held index is a rebuild's")
      (let [g2 (step g1 db2) ]
        (is (= '#{app.pt-calc-test/neg2-test} (:stale (step (program/reach-indexed g1 in) db2))) "a test added: that test only")
        (is (= (rebuilt db2) (held g2)) "the changes of two steps, with the first not yet indexed: a rebuild's")
        (let [g3 (step (program/reach-indexed g2 in) db3)]
          (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test app.pt-calc-test/neg-test app.pt-calc-test/neg2-test} (:stale g3))
              "a namespace's plain form changed (its ns form): every test of it")
          (is (= (rebuilt db3) (held g3)))
          (let [g4 (step (program/reach-indexed g3 in) db4)]
            (is (= '#{app.pt-calc-test/neg-test app.pt-calc-test/neg2-test} (:stale g4)) "neg redefined: its two tests")
            (is (= (rebuilt db4) (held g4)))
            (let [g5 (step (program/reach-indexed g4 in) db0)]
              (is (= (rebuilt db0) (held g5)) "back to the first program (tests removed): its index again")
              (is (not-any? #{'app.pt-calc-test/neg2-test} (keys (program/index-of g5))) "the removed test has no answer"))))))
    (testing "other inputs: every answer again"
      (let [in2 (assoc in :env "f") g (program/reach-indexed g0 in2)]
        (is (= (ts/reach-index db0 in2) (program/index-of g)))
        (is (not= (program/index-of g0) (program/index-of g)))))))

(defn- with-dir
  "f called with a fresh verdict cache directory under this checkout's target/tmp, removed after."
  [f] (let [dir (str (seon.config/checkout) "/target/tmp/verdicts-" (random-uuid))]
        (try (f dir) (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(defn- run-plan "bin/test's plan of digests against the verdicts the cache at dir holds." [digests dir]
  (proofs/plan nil digests (seon.fx/stored-results dir digests) #{}))

(defn- record-ran!
  "Cache ran ({test result}) at dir as bin/test does."
  [dir ran] (seon.fx/write-verdicts! dir {:id "program-test"} ran {}))

(deftest a-second-run-with-nothing-changed-runs-nothing
  (with-dir
   (fn [conn]
  (let [db (calc-db)
        digests (select-keys (ts/reach-digests db {:env "e" :tree "t" :config {}}) calc-tests)
        plan #(run-plan digests conn)
        first-plan (plan)
        ran (doto (proofs/run-tests! (:run first-plan) digests (fn [_])) (->> (record-ran! conn)))
        second-plan (plan)]
    (is (= calc-tests (set (:run first-plan))) "nothing recorded: every test runs")
    (is (= {:pass 1 :fail 0 :error 0} (select-keys (ran 'app.pt-calc-test/add-test) [:pass :fail :error])))
    (is (= [] (:run second-plan)) "the second run runs nothing")
    (is (= calc-tests (set (keys (:reused second-plan)))) "and reports every result reused")
    (is (= (digests 'app.pt-calc-test/neg-test) (:digest ((:reused second-plan) 'app.pt-calc-test/neg-test))))))))

(defn- checkout!
  "A git checkout at a new directory holding files ({relative path text}), all added."
  [label files]
  (let [d (scratch-dir label)]
    (doseq [[p t] files] (.mkdirs (.getParentFile (java.io.File. (str d "/" p)))) (spit (str d "/" p) t))
    (sh/sh "git" "-C" d "init" "-q")
    (sh/sh "git" "-C" d "add" ".")
    d))

(deftest a-result-recorded-in-one-checkout-is-reused-in-another
  (with-dir (fn [conn]
  (let [files {"deps.edn" "{}" "resources/seon/schemas/x.edn" "{:x :int}" "src/app/a.clj" "(ns app.a)" "docs/a.md" "# a"}
        a (checkout! "a" files)
        b (checkout! "b" files)
        db (calc-db)
        in-a (fx/inputs a)
        da (select-keys (ts/reach-digests db in-a) calc-tests)]
    (is (= in-a (fx/inputs b)) "the same content at another path: the same inputs")
    (record-ran! conn (proofs/run-tests! (:run (run-plan da conn)) da (fn [_])))
    (is (= [] (:run (run-plan (select-keys (ts/reach-digests db (fx/inputs b)) calc-tests) conn)))
        "checkout b reuses every result checkout a recorded")
    (spit (str b "/docs/a.md") "# a, edited")
    (is (= (:env in-a) (:env (fx/inputs b))) "prose is no input of a test that reads no files")
    ;; the shipped config is no input of every test (root, 2026-10-27: a batch's greens were reused
    ;; by none, because :env moved in 8 of 12 batches): its values are :config's, taken by the keys
    ;; a test reads
    (.mkdirs (java.io.File. (str b "/resources/seon/config")))
    (spit (str b "/resources/seon/config/default.edn") "{:a 1}")
    (is (= (:env in-a) (:env (fx/inputs b))) "the shipped config file is no input of every test")
    (spit (str b "/resources/seon/schemas/x.edn") "{:x :string}")
    (is (= (:env in-a) (:env (fx/inputs b))) "a schema resource is no input of every test: its entries are program facts, taken by the tests whose reach names them")
    (is (not= (get-in in-a [:config :seon.env/schemas]) (get-in (fx/inputs b) [:config :seon.env/schemas]))
        "and a class of its own, for a reach that walks a db's whole schema (seon.reconcile/index)")
    (spit (str b "/src/app/a.clj") "(ns app.a \"edited\")")
    (is (= (:env in-a) (:env (fx/inputs b))) "a src file is no input of every test: its forms are program facts")
    (is (not= (get-in in-a [:config :seon.env/src]) (get-in (fx/inputs b) [:config :seon.env/src]))
        "and a class of its own, for a reach that reads the whole program's text (a docstring)")
    (spit (str b "/deps.edn") "{:paths []}")
    (is (= (:env in-a) (:env (fx/inputs b))) "deps.edn is a class of its own too")
    (.mkdirs (java.io.File. (str b "/native")))
    (spit (str b "/native/x.c") "int x;")
    (is (not= (:env in-a) (:env (fx/inputs b))) "native/, the pins and the fixtures are every test's")))))

(deftest a-test-takes-only-the-file-classes-its-reach-reads
  ;; root, 2026-10-27: :env moved in 8 of 12 batches, and with it every digest. bin/, web/, system.edn,
  ;; deps.edn and the schema resources are classes (seon.fx/inputs' :seon.env/* entries of
  ;; :config); a test takes a class when its reach reads it (seon.program/env-class-readers), and
  ;; every file class when it reads files or runs a process (a pin it takes by reach alone).
  ;; A fixture of one test for each reader (recorded by the facts the compiler gives, their refs) and a
  ;; pure one, on the world: the classes are the readers' (seon.program/env-class-readers), not the
  ;; program's size (this took the checkout's whole kept graph, 321 M ticks)
  (let [form (fn [nsym nm kind refs]
               (fn [db] (program/record-tx db {:id (str nsym "/" nm) :src (str "(defn " nm ")") :ns nsym :ns-form? false
                                               :defs [{:var (symbol (str nsym) nm) :kind kind}]
                                               :declares #{} :methods [] :impls [] :refs refs :requires #{} :banned #{} :inline-defs #{}})))
        db (mt/with (mt/world)
             (form 'app.fc-test "pure" :test #{})
             (form 'app.fc-test "reads-program" :test '#{seon.test-store/kept-program-store})
             (form 'app.fc-test "starts-a-process" :test '#{seon.lib/fork!})
             (form 'app.fc-test "reads-schemas" :test '#{seon.reconcile/index})
             (form 'app.fc-test "runs-a-script" :test '#{clojure.java.shell/sh})
             (form 'app.fc-test "serves" :test '#{seon.web/handler})
             (form 'app.fc-test "reads-system" :test '#{seon.system/read-decl})
             (form 'app.fc-test "reads-deps" :test '#{seon.fork-server/dev-classpath!})
             (form 'app.fc "contracts-step" :fn '#{seon.contract/uncontracted}))
        g (program/reach-graph db nil)
        in {:env "e" :tree "t" :config {}}
        a (update-vals (program/graph-index g in nil) :digest)
        moved (fn [c] (let [b (update-vals (program/graph-index g (assoc-in in [:config c] "other") nil) :digest)]
                        (set (for [[t d] a :when (not= d (b t))] (symbol (name t))))))
        bin (moved :seon.env/bin) web (moved :seon.env/web) system (moved :seon.env/system) deps (moved :seon.env/deps)
        schemas (moved :seon.env/schemas) src (moved :seon.env/src)]
    (is (src 'reads-program) "a test reading the checkout's whole program takes the src class")
    (is (not (src 'pure)) "a pure test takes no src class")
    (is (not (src 'starts-a-process)) "nor does a test that only starts processes")
    ;; bin/check contracts judges every var of src (seon.contract/uncontracted): batch 18 added one uncontracted with
    ;; the step green at its digest, which only its own code made (2026-10-30)
    (let [step (fn [in] (:digest (get (program/graph-index g in {"contracts" '[app.fc/contracts-step]}) "contracts")))]
      (is (not= (step in) (step (assoc-in in [:config :seon.env/src] "other"))) "the contracts step takes the src class"))
    ;; a-move-of-main-costs-what-moved went red when 14 attributes joined the schema, its digest unmoved:
    ;; seon.reconcile/index filters every attribute the schema declares by namespace (docs/source.md#tests-run-by-reach)
    (is (schemas 'reads-schemas) "a test reaching seon.reconcile/index takes the schema class")
    (is (not (schemas 'pure)) "a pure test takes no schema class")
    (doseq [[c m] [[:bin bin] [:web web] [:system system] [:deps deps]]]
      (is (m 'starts-a-process) (str "a test starting processes takes the " c " class"))
      (is (not (m 'pure)) (str "a pure test takes no " c " class")))
    ;; each reader takes its own class and no other of the four: some tests, not every test
    (doseq [[reader c m] [['runs-a-script :bin bin] ['serves :web web] ['reads-system :system system] ['reads-deps :deps deps]]]
      (is (m reader) (str reader " takes the " c " class"))
      (is (= #{reader 'starts-a-process} m) (str "and only it and the process test take " c ": " (pr-str m))))))

(deftest a-schema-key-a-test-names-is-an-edge-to-its-entry
  ;; root, 2026-10-27: 896 tests took the schema resources on every schema edit. A schema key a form
  ;; names as a literal keyword (a valid? or a registered error variant) is an edge to that entry:
  ;; an edit moves the tests that reach it, and the tests that name no entry keep their digest
  (let [schema (fn [db k form] (d/db-with db [{:schema/key k :schema/form form}]))
        db (-> (record (empty-db) 'user "(ns app.pt-sk)" 'user "(ns app.pt-sk-test (:require [clojure.test :refer [deftest is]]))"
                       'app.pt-sk-test "(deftest names-test (is (= 1 (count [:app.sk/k]))))"
                       'app.pt-sk-test "(deftest other-test (is (= 1 1)))")
               (schema :app.sk/k ":int") (schema :app.sk/z ":int"))
        dg (fn [db] (ts/reach-digests db {:env "e" :tree "t" :config {}}))
        before (dg db)
        changed (dg (schema db :app.sk/k ":string"))
        other-entry (dg (schema db :app.sk/z ":string"))]
    (is (not= (before 'app.pt-sk-test/names-test) (changed 'app.pt-sk-test/names-test)) "the test naming the entry moves with it")
    (is (= (before 'app.pt-sk-test/other-test) (changed 'app.pt-sk-test/other-test)) "a test naming no entry keeps its digest")
    (is (= (before 'app.pt-sk-test/names-test) (other-entry 'app.pt-sk-test/names-test)) "and an edit to another entry moves neither")))

(deftest a-render-a-schema-entry-declares-is-in-its-tests-reach
  ;; a schema entry's properties name its renders (:seon.render/html): a test reaching the entry reaches
  ;; them, so a render's body edited moves the test (it kept its digest, and a red recorded while the render
  ;; was unfinished was later marked flaky at the same digest)
  (let [schema (fn [db k form] (d/db-with db [{:schema/key k :schema/form form}]))
        db (-> (record (empty-db) 'user "(ns app.pt-sr)" 'app.pt-sr "(defn show [x] (str x))"
                       'user "(ns app.pt-sr-test (:require [clojure.test :refer [deftest is]]))"
                       'app.pt-sr-test "(deftest names-test (is (= 1 (count [:app.sr/k]))))"
                       'app.pt-sr-test "(deftest other-test (is (= 1 1)))")
               (schema :app.sr/k "[:map {:seon.render/html app.pt-sr/show}]"))
        dg (fn [db] (ts/reach-digests db {:env "e" :tree "t" :config {}}))
        before (dg db)
        edited (dg (record db 'app.pt-sr "(defn show [x] (str \"<\" x \">\"))"))]
    (is (not= (before 'app.pt-sr-test/names-test) (edited 'app.pt-sr-test/names-test)) "the test reaching the entry moves with its render")
    (is (= (before 'app.pt-sr-test/other-test) (edited 'app.pt-sr-test/other-test)) "a test reaching neither keeps its digest")))

(deftest a-schema-entry-added-changed-or-removed-is-updated-not-rebuilt
  ;; task seon.program/reach-update-takes-schema-entries: a merge that adds a schema entry rebuilt the graph (154M
  ;; ticks, 892 MB on Seon's program, 4,702 forms). The update is a rebuild's at every step: an entry added that a
  ;; contract, another entry and a form's keyword already name (they gain the edge), changed, removed, and back
  (let [schema (fn [db k form] (d/db-with db [{:schema/key k :schema/form form}]))
        unschema (fn [db k] (d/db-with db [[:db/retractEntity [:schema/key k]]]))
        in {:env "e" :tree "t" :config {}}
        db0 (-> (record (empty-db) 'user "(ns app.pt-su)" 'user "(ns app.pt-su-test (:require [clojure.test :refer [deftest is]]))"
                        'app.pt-su "(defn f {:malli/schema [:=> [:cat :app.su/late] :int]} [x] 1)"
                        'app.pt-su "(defn other [x] 2)"
                        'app.pt-su-test "(deftest contract-test (is (= 1 (app.pt-su/f 1))))"
                        'app.pt-su-test "(deftest names-test (is (= 1 (count [:app.su/late]))))"
                        'app.pt-su-test "(deftest other-test (is (= 2 (app.pt-su/other 1))))")
                (schema :app.su/k ":int") (schema :app.su/entry "[:map [:a :app.su/late]]"))
        db1 (schema db0 :app.su/late ":string")
        db2 (schema db1 :app.su/k ":keyword")
        db3 (unschema db2 :app.su/late)
        db4 (-> db3 (schema :app.su/late ":int") (unschema :app.su/k))
        rebuilt #(ts/reach-index % in)
        update #(program/reach-update % %2)
        g0 (program/reach-indexed (program/reach-graph db0 nil) in)
        g1 (update g0 db1) g2 (update (program/reach-indexed g1 in) db2) g3 (update g2 db3) g4 (update (program/reach-indexed g3 in) db4)]
    (doseq [[label g db] [["an entry that a contract, an entry and a keyword name is added" g1 db1] ["one changed" g2 db2]
                          ["the added one removed, with the indexed step skipped" g3 db3] ["one added, another removed" g4 db4]]]
      (is (= (rebuilt db) (program/graph-index g in nil)) (str label ": the update's digests are a rebuild's"))
      (is (= (rebuilt db) (program/index-of (program/reach-indexed g in))) (str label ": and the index it holds")))
    (is (= '#{app.pt-su-test/contract-test app.pt-su-test/names-test} (:stale g1)) "the tests reaching the new entry are what moved")
    (is (= (:rows g1) (:rows (program/reach-graph db1 nil)) ) "rows as a rebuild's, the keyword edges kept up")
    (is (= (rebuilt db0) (program/graph-index (update g4 db0) in nil)) "and back to the first program")))

(defn- path
  "The shortest chain of forms (as the vars each defines, or its id) from var a's form (or the
  form of id a) to var b's in reach graph g, or nil."
  [{:keys [adj defs rows]} a b]
  (let [to (peek (get defs b)) start (if (string? a) a (peek (get defs a)))
        show (fn [id] (or (first (keep (fn [[k s]] (when (= :var k) s)) (:defines (rows id)))) id))]
    (loop [fr [start] from {start nil}]
      (when (seq fr)
        (if (contains? from to)
          (mapv show (reverse (take-while some? (iterate from to))))
          (let [st (for [id fr w (adj id) :when (not (contains? from w))] [w id])]
            (recur (vec (distinct (map first st))) (reduce (fn [m [w id]] (if (contains? m w) m (assoc m w id))) from st))))))))

(deftest a-test-that-starts-a-process-reaches-the-program-it-runs
  ;; ctx-atom, 2026-10-27: seon.server-test's in-agent tests kept one reach digest across two fixes to
  ;; code their agent runs (seon.turn/observe, seon.merge/changed-vars), so a stored red and a later
  ;; green shared it and bin/test listed them flaky. The agent's code is reached over a socket and as
  ;; a quoted form root sends it: a form using a process var has an edge to the process's entry vars
  ;; (:seon.config.source/process-entry)
  (let [here (seon.config/checkout)
        in (fx/inputs here)
        g (kept-graph here) ;; the graph as bin/test keeps it, brought to date
        idx (program/graph-index g in nil)
        process (set (keep (fn [[t v]] (when (:process? v) t)) idx))
        reaching (set (filter (set (keys idx)) (program/tests-reaching g 'seon.turn/observe)))]
    (is (seq process) "there are process tests")
    (is (every? reaching process) "every test that starts a process reaches the code the agent it starts runs")
    (is (every? #(get (:defs g) %) (cfg/value :seon.config.source/process-entry)) "and every configured entry var is in the program: a renamed one drops its edge in silence")
    (is (every? reaching '[seon.server-test/the-context-op-answers-what-a-model-is-sent seon.server-test/done-answers-the-judges-reasons]))
    (is (not (reaching 'seon.reader-test/prose-without-a-fence-is-a-comment)) "a pure test does not")
    ;; owner, 2026-10-27 ("no tests should include all of src"): the entry was seon.main/-main, a
    ;; root's whole boot (1,512 vars), so a change anywhere in it deferred every process test to the
    ;; merge. The entry is what the process runs for the test: the agent's turn, and of the server's
    ;; answers (seon.server/op-answer) only the methods of the ops the test sends
    (let [method (fn [op] (first (for [id (get (:methods g) 'seon.server/op-answer)
                                       :when (contains? (:defines ((:rows g) id)) [:method 'seon.server/op-answer (pr-str op)])]
                                   id)))
          tests-of (fn [ids] (set (for [id ids [k s] (:defines ((:rows g) id)) :when (and (= :var k) ((:tests g) s))] s)))
          driving (fn [op] (tests-of (program/reached-by (:rev g) [(method op)])))
          done (driving :done) evals (driving :eval)]
      (is (get (:defs g) 'seon.generation/generation) "the boot's var is in the program")
      ;; a test reaching it by its own code (seon.source-test's restart calls seon.e2e/loop-turn!) still does
      (is (empty? (keep #(path g % 'seon.generation/generation) (cons 'seon.turn/work! (get (:methods g) 'seon.server/op-answer))))
          "what a process runs for a test (the turn, each op's answer) does not reach a root's boot")
      (is (and (method :done) (method :eval)) "each op is a method of its own")
      (is (done 'seon.server-test/done-answers-the-judges-reasons) "a test that sends :done reaches the :done method")
      (is (and (process 'seon.server-test/an-agent-reads-its-task-running) (not (done 'seon.server-test/an-agent-reads-its-task-running)))
          "a process test that sends no :done does not")
      (is (< 0 (count done) (count process)) (str (count done) " of " (count process) " process tests send :done"))
      (is (evals 'seon.server-test/an-agent-reads-its-task-running) "every in-agent test sends :eval (in-agent does) and reaches its method"))))

(deftest a-var-an-eval-string-names-moves-the-test-that-sends-it
  ;; root, 2026-10-27: code a test sends a process as a string (in-agent's evals) is read by the one
  ;; reader at analysis (seon.program/unit-account), its names an edge in a test's namespace as a
  ;; quoted form's are; a string of fragments (str "(f " x ")") reads once its delimiters are
  ;; repaired. An update after the named var is redefined, added or removed agrees with a rebuild
  (let [in {:env "e" :tree "t" :config {}}
        tests ['user "(ns app.pt-pe-test (:require [clojure.test :refer [deftest is]]))"
               'app.pt-pe-test "(deftest starts-test (is (= 1 (seon.lib/fork! \"(app.pt-pe/f 1)\"))))"
               'app.pt-pe-test "(deftest built-test (is (= 1 (seon.lib/fork! (str \"(app.pt-pe/g \" 1 \")\")))))"
               'app.pt-pe-test "(deftest pure-test (is (= 1 1)))"]
        with-f (fn [body] (apply record (record (empty-db) 'user "(ns app.pt-pe)" 'app.pt-pe (str "(defn f [x] " body ")") 'app.pt-pe (str "(defn g [x] " body ")")) tests))
        db0 (apply record (empty-db) tests)
        db1 (with-f "1") db2 (with-f "2")
        dg (fn [g] (update-vals (program/graph-index g in nil) :digest))
        rebuilt (fn [db] (dg (program/reach-graph db nil)))
        g0 (program/reach-graph db0 nil) g1 (program/reach-update g0 db1) g2 (program/reach-update g1 db2)]
    (is (= (rebuilt db1) (dg g1)) "the named var added: the update agrees with a rebuild")
    (is (= (rebuilt db2) (dg g2)) "redefined: the same")
    (is (= (rebuilt db0) (dg (program/reach-update g2 db0))) "removed: the same")
    (is (not= ((dg g1) 'app.pt-pe-test/starts-test) ((dg g2) 'app.pt-pe-test/starts-test)) "the test whose eval string names the var moves with it")
    (is (not= ((dg g1) 'app.pt-pe-test/built-test) ((dg g2) 'app.pt-pe-test/built-test)) "and so does one whose string is built from fragments")
    (is (= ((dg g1) 'app.pt-pe-test/pure-test) ((dg g2) 'app.pt-pe-test/pure-test)) "a pure test does not")))

(deftest an-op-answer-redefined-moves-the-tests-that-send-it-by-an-update-too
  ;; a form that starts a process, or sends an op from a test's namespace, has an edge to
  ;; seon.server/op-answer's method of each op it sends (its :form/ops): an update after a method
  ;; is added, redefined or removed agrees with a rebuild, and moves only the tests sending its op
  ;; (two methods of ops nobody sends, :pt-a and :pt-b, added to the live multimethod and removed after)
  (let [in {:env "e" :tree "t" :config {}}
        tests ['user "(ns app.pt-op-test (:require [clojure.test :refer [deftest is]]))"
               'app.pt-op-test "(deftest a-test (is (= 1 (seon.lib/fork! {:op :pt-a}))))"
               'app.pt-op-test "(deftest b-test (is (= 1 (seon.lib/fork! {:op :pt-b}))))"
               'app.pt-op-test "(defn ask [x] {:op :pt-b :x x})"]
        server (fn [a] ['seon.server "(defmethod op-answer :pt-a [_ _ _] {:a 1})" 'seon.server (str "(defmethod op-answer :pt-b [_ _ _] {:b " a "})")])
        db0 (apply record (empty-db) tests)
        db1 (apply record (apply record (empty-db) (server 1)) tests)
        db2 (apply record db1 (server 2))
        dg (fn [g] (update-vals (program/graph-index g in nil) :digest))
        rebuilt (fn [db] (dg (program/reach-graph db nil)))
        g0 (program/reach-graph db0 nil) g1 (program/reach-update g0 db1) g2 (program/reach-update g1 db2)]
    (try
      (is (= (rebuilt db1) (dg g1)) "the methods added: the update agrees with a rebuild")
      (is (= (rebuilt db2) (dg g2)) "one redefined: the same")
      (is (= (rebuilt db0) (dg (program/reach-update g2 db0))) "removed: the same")
      (is (not= ((dg g1) 'app.pt-op-test/b-test) ((dg g2) 'app.pt-op-test/b-test)) "the test sending :pt-b moves with its answer")
      (is (= ((dg g1) 'app.pt-op-test/a-test) ((dg g2) 'app.pt-op-test/a-test)) "the one sending only :pt-a does not")
      (finally (remove-method seon.server/op-answer :pt-a) (remove-method seon.server/op-answer :pt-b)))))

(deftest a-test-takes-the-pins-of-the-libraries-its-reach-runs-on
  ;; root, 2026-10-27: a pin bump moved every digest in 3 of the last 12 batches (datahike 3, konserve
  ;; 2, jolt 2). A pin named by :seon.config.source/pin-namespaces is a :seon.pin/NAME entry of
  ;; :config, taken by a test whose reach references a namespace its library provides (or runs on),
  ;; a test that reads files or runs a process too; the runtime core's pins are no test's (ruling D)
  (let [here (seon.config/checkout)
        in (fx/inputs here)
        sample (sampled-repo "pins" 1000 ;; the first file of the tree and the four named: seon.db and its test reach lmdb and zstd, datahike.query-test datahike, reader-test nothing
                                 ["test/datahike/query_test.clj" "test/seon/reader_test.clj" "src/seon/db.clj" "test/seon/db_test.clj"])
        g (program/reach-graph (runner/program-db sample (ts/program-store)) (:data-refs in))
        a (update-vals (program/graph-index g in nil) :digest)
        all-tests (set (keys a))
        ;; each class once: the checks below ask the same sets several times, a graph-index of the whole program each
        moved (memoize (fn [c] (let [b (update-vals (program/graph-index g (assoc-in in [:config c] "other") nil) :digest)]
                                 (set (for [[t d] a :when (not= d (b t))] t)))))]
    (is (every? #(contains? (:config in) %) [:seon.pin/datahike :seon.pin/konserve :seon.pin/malli :seon.pin/crypto]) "the pins the config names are classes of the inputs")
    (is ((moved :seon.pin/datahike) 'datahike.query-test/a-nested-call-in-a-predicate-is-refused) "a test that calls datahike takes its pin")
    ;; the runtime's pins (Jolt, Chez) are no class: every test's :env takes them (a-runtime-pin-moves-every-digest)
    (is (every? #(contains? (:config in) %) [:seon.pin/lmdb :seon.pin/zstd :seon.pin/llama.cpp :seon.pin/awesome-jolt]) "every other submodule is a class")
    (is (not-any? #(contains? (:config in) %) [:seon.pin/jolt :seon.pin/chez]) "the runtime's pins are in :env, no class")
    (is (= #{} (moved :seon.pin/crypto)) "a library no reached namespace names is no test's (src calls no jolt.crypto)")
    (doseq [c [:seon.pin/datahike :seon.pin/lmdb :seon.pin/zstd]]
      (is (< 0 (count (moved c)) (count all-tests)) (str (count (moved c)) " of " (count all-tests) " tests take " c ": some, not every test"))
      (is (not ((moved c) 'seon.reader-test/prose-without-a-fence-is-a-comment)) (str "a pure test takes no " c)))
    (is (< (count (moved :seon.pin/lmdb)) (* 0.5 (count all-tests))) "an lmdb bump moves under half the tests")
    (is (< (count (moved :seon.pin/crypto)) (count (moved :seon.pin/datahike))) "fewer tests run on the crypto library than on datahike")))

(deftest an-uncommitted-edit-in-a-submodule-is-an-input
  ;; pure-tests 2026-10-23: tree-id stages a submodule at its HEAD, while bin/build copies and
  ;; the test runtime loads its files as they are: a dependency edit not yet committed left
  ;; every digest as it was, so a green proven on the old files was reused on the new. A submodule
  ;; is a class (seon.config/pin-class), taken by the tests whose reach runs on it
  (let [p (checkout! "with-sub" {"deps.edn" "{}"})
        s (str p "/reference-code/lib")
        core (str s "/core.clj")]
    (.mkdirs (java.io.File. s))
    (spit core "(ns lib.core)")
    (doseq [args [["init" "-q"] ["add" "."] ["-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "lib"]]]
      (apply sh/sh "git" "-C" s args))
    (sh/sh "git" "-C" p "add" "reference-code/lib")
    (let [lib #(get-in (fx/inputs p) [:config :seon.pin/lib])
          before (lib)]
      (is (string? before) "the submodule is a class of the inputs")
      (spit core "(ns lib.core) (def edited 1)")
      (is (not= before (lib)) "an edit in the submodule's files moves its class")
      (spit core "(ns lib.core)")
      (is (= before (lib)) "its files back as committed: the same input as before"))))

(deftest a-runtime-pin-moves-every-digest
  ;; 2026-10-29: seon.cost-test/the-unit-table-is-current stayed green across a Jolt pin that moved
  ;; its numbers, since the runtime core took no pin (root's ruling D, 2026-10-27). The gitlinks of
  ;; :seon.config.source/runtime-pins (Jolt, Chez) are in every test's and every contract key's :env;
  ;; a bump re-runs everything once. docs/source.md#tests-run-by-reach
  (let [p (checkout! "runtime-pins" {"deps.edn" "{}" "docs/a.md" "# a"})
        commit (fn [s msg] (doseq [args [["init" "-q"] ["add" "."] ["-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" msg]]]
                             (apply sh/sh "git" "-C" s args)))
        n (volatile! 0)
        bump (fn [name] (let [s (str p "/reference-code/" name)]
                          ;; a new text per bump, so each is a commit of its own (a count, never a random: test-inputs)
                          (spit (str s "/core.clj") (str "(ns " name ") " (vswap! n inc)))
                          (sh/sh "git" "-C" s "add" ".")
                          (sh/sh "git" "-C" s "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "bump")
                          (sh/sh "git" "-C" p "add" (str "reference-code/" name))))
        c (assoc cfg/config :seon.config.source/runtime-pins #{"reference-code/jolt" "reference-code/chez"})
        env #(:env (fx/inputs p c (cfg/checkout)))]
    (doseq [n ["jolt" "chez"]]
      (.mkdirs (java.io.File. (str p "/reference-code/" n)))
      (spit (str p "/reference-code/" n "/core.clj") (str "(ns " n ")"))
      (commit (str p "/reference-code/" n) n)
      (sh/sh "git" "-C" p "add" (str "reference-code/" n)))
    (let [before (env)]
      (is (not-any? #(contains? (:config (fx/inputs p c (cfg/checkout))) %) [:seon.pin/jolt :seon.pin/chez]) "no class of their own: they are :env")
      (bump "jolt")
      (let [after-jolt (env)]
        (is (not= before after-jolt) "a Jolt bump moves :env, which every test's digest takes")
        (bump "chez")
        (is (not= after-jolt (env)) "and a Chez bump")
        (let [e (env)]
          (spit (str p "/docs/a.md") "# c")
          (is (= e (env)) "an unrelated file (prose) moves no :env"))))
    (testing "the digest of every test and every contract key takes :env"
      (let [here (seon.config/checkout)
            in (fx/inputs here)
            sample (sampled-repo "runtime-pins" 1000 ["test/seon/reader_test.clj" "src/seon/reader.clj"])
            g (program/reach-graph (runner/program-db sample (ts/program-store)) (:data-refs in))
            roots {"k" ['seon.reader/read-text]}
            at (fn [env] [(update-vals (program/graph-index g (assoc in :env env) nil) :digest)
                          (update-vals (program/graph-index g (assoc in :env env) roots) :digest)])
            [t1 k1] (at "jolt-1") [t2 k2] (at "jolt-2") [t3 _] (at "jolt-1")]
        (is (seq t1))
        (is (every? #(not= (t1 %) (t2 %)) (keys t1)) "every test's digest moves with the runtime pin")
        (is (= t1 t3) "and returns with it")
        (is (not= (k1 "k") (k2 "k")) "a contract key's digest too")))))

;; ---- refs the expansion and the quote leave (task seon.program/refs-name-quoted-symbols-and-macro-uses)

(defn- refs-of [db sym]
  (set (d/q '[:find [?r ...] :in $ ?s :where [?v :var/name ?s] [?v :var/form ?f] [?f :form/refs ?r]] db sym)))

(deftest a-quoted-name-is-no-reference-but-in-a-macros-template
  ;; root's ruling, 2026-10-25: the reach follows calls; a quoted name is data, but what a
  ;; macro's template quotes is its expansion's code
  (let [db (record (empty-db) 'user "(ns app.pt-q)" 'app.pt-q "(defn g [] 1)"
                   'app.pt-q "(defn f [] [`g 'app.pt-q/g {:render 'app.pt-q/g}])"
                   'app.pt-q "(defn c [] (g))"
                   'app.pt-q "(defmacro t [] `(g))")]
    (is (not (contains? (refs-of db 'app.pt-q/f) 'app.pt-q/g)) "a syntax-quoted and a quoted name, in a map too: data, no reference")
    (is (contains? (refs-of db 'app.pt-q/c) 'app.pt-q/g) "a call is")
    (is (contains? (refs-of db 'app.pt-q/t) 'app.pt-q/g) "and a name a macro's template quotes, which its expansion calls")
    (is (= '[app.pt-q/c app.pt-q/f app.pt-q/t] (seon.doc/callers db 'app.pt-q/g)) "who calls or names g: f names it as data")))

(deftest a-macro-use-is-a-ref
  (let [db (record (empty-db) 'user "(ns app.pt-qm)" 'app.pt-qm "(defmacro m [x] x)"
                   'app.pt-qm "(defn u [] (m 1))"
                   'app.pt-qm "(defmacro mdo [] `(do (defn ~'w [] 1) (defn ~'w2 [] 2)))"
                   'app.pt-qm "(mdo)"
                   'app.pt-qm "(defmacro m2 [x] `(m ~x))")]
    (is (contains? (refs-of db 'app.pt-qm/u) 'app.pt-qm/m) "the macro a form expanded")
    (is (contains? (refs-of db 'app.pt-qm/w) 'app.pt-qm/mdo) "a top-level macro call that expands to a do: each unit's form names it")
    (is (contains? (refs-of db 'app.pt-qm/m2) 'app.pt-qm/m) "a macro whose template names another: the expansion chain is closed")))

(deftest a-changed-macro-moves-the-digest-of-each-test-that-expands-it
  (let [db (record (empty-db) 'user "(ns app.pt-mac)" 'app.pt-mac "(defmacro m [x] `(inc ~x))"
                   'user "(ns app.pt-mac-test (:require [clojure.test :refer [deftest is]] [app.pt-mac :as a]))"
                   'app.pt-mac-test "(deftest m-test (is (= 2 (a/m 1))))"
                   'app.pt-mac-test "(deftest plain-test (is (= 1 1)))")
        ts '#{app.pt-mac-test/m-test app.pt-mac-test/plain-test}
        before (select-keys (ts/reach-digests db {:env "e" :tree "t" :config {}}) ts)
        db' (record db 'app.pt-mac "(defmacro m [x] `(+ 2 ~x))")
        after (select-keys (ts/reach-digests db' {:env "e" :tree "t" :config {}}) ts)]
    (is (not= (before 'app.pt-mac-test/m-test) (after 'app.pt-mac-test/m-test)) "the macro's body changed: the test expanding it moved (no stale green)")
    (is (= (before 'app.pt-mac-test/plain-test) (after 'app.pt-mac-test/plain-test)) "a test not expanding it kept its digest")
    (is (= '#{app.pt-mac-test/m-test} (selected db db')) "and every run's selection finds exactly it")))

(deftest a-var-a-test-resolves-at-run-time-moves-its-digest
  ;; a constant symbol passed to resolve, ns-resolve or requiring-resolve is a reference (the
  ;; compiler's facts): a green at a digest that left the var out was reused after it changed,
  ;; and a task whose proof resolves a var it defines closed with no turn (app.proofs/gens-test)
  (let [db (record (empty-db) 'user "(ns app.pt-res)" 'app.pt-res "(defn f [] 1)"
                   'user "(ns app.pt-res-test (:require [clojure.test :refer [deftest is]]))"
                   'app.pt-res-test "(deftest r-test (is (= 1 ((resolve 'app.pt-res/f)))))"
                   'app.pt-res-test "(deftest ns-test (is (= 1 ((ns-resolve 'app.pt-res 'f)))))"
                   'app.pt-res-test "(deftest req-test (is (= 1 ((requiring-resolve 'app.pt-res/f)))))"
                   'app.pt-res-test "(deftest later-test (is (= 2 ((resolve 'app.pt-res/g)))))"
                   'app.pt-res-test "(deftest plain-test (is (= 1 1)))"
                   'app.pt-res-test "(deftest data-test (is (= 'app.pt-res/f (first (list 'app.pt-res/f)))))")
        ts '#{app.pt-res-test/r-test app.pt-res-test/ns-test app.pt-res-test/req-test app.pt-res-test/later-test app.pt-res-test/plain-test app.pt-res-test/data-test}
        dg (fn [db] (select-keys (ts/reach-digests db {:env "e" :tree "t" :config {}}) ts))
        before (dg db)
        changed (dg (record db 'app.pt-res "(defn f [] 3)"))
        defined (dg (record db 'app.pt-res "(defn g [] 2)"))]
    (doseq [t '[app.pt-res-test/r-test app.pt-res-test/ns-test app.pt-res-test/req-test]]
      (is (not= (before t) (changed t)) (str t ": the var it resolves changed, so its digest moved")))
    (is (not= (before 'app.pt-res-test/later-test) (defined 'app.pt-res-test/later-test))
        "the var it resolves came to exist: its digest moved")
    (is (= (before 'app.pt-res-test/plain-test) (changed 'app.pt-res-test/plain-test) (defined 'app.pt-res-test/plain-test))
        "a test resolving nothing kept its digest")
    (is (= (before 'app.pt-res-test/later-test) (changed 'app.pt-res-test/later-test)) "f is no var later-test resolves")
    ;; root, 2026-10-27: a name quoted in a test's namespace is one the test hands over by name
    ;; (seon.program-test/a-name-a-test-hands-over-quoted-is-what-it-reaches): a rerun, never a stale green
    (is (not= (before 'app.pt-res-test/data-test) (changed 'app.pt-res-test/data-test)) "a name a test's namespace quotes is what it reaches")))

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
        dg (fn [db] (select-keys (ts/reach-digests db {:env "e" :tree "t" :config {}}) ts))
        before (dg db)
        after (dg (schema db :app.pt-sc/n "[:and :string :app.pt-sc/base]"))
        after-base (dg (schema db :app.pt-sc/base "pos-int?"))]
    (is (not= (before 'app.pt-sc-test/h-test) (after 'app.pt-sc-test/h-test)) "the schema h's contract names changed: the test calling h moved")
    (is (not= (before 'app.pt-sc-test/h-test) (after-base 'app.pt-sc-test/h-test)) "and a schema that schema names, on")
    (is (= (before 'app.pt-sc-test/plain-test) (after 'app.pt-sc-test/plain-test) (after-base 'app.pt-sc-test/plain-test))
        "a test not reaching h kept its digest")))

(deftest the-same-tree-gives-the-same-digests-in-any-checkout-and-any-run
  ;; a quoted name counted as a ref only when its var existed yet (then by namespace, now by name: seon.program/quoted-names), so
  ;; the digests followed what the process had analyzed before: 43 of 387 equal on a second
  ;; import of the same tree. The same commit, imported twice and from a copy at another path,
  ;; gives the same digest for every test: a second run reruns nothing, two worktrees share.
  ;; And root's import of it (seon.source/import-tx of :seon.config.source/dirs, as
  ;; seon.root/import-source! reads them) gives bin/test's digests: bin/test imported src and test
  ;; only, root the schemas too, so 882 of 922 differed and the judge reused 0 of 917 greens
  ;; bin/prove stored (task an-outside-branch-hands-back-through-the-store, experiment 2)
  ;; A small tree of eight files in two repositories (tiny-tree): a function calling another file's, and names quoted
  ;; before the var they name is imported (src/app/ta.clj quotes app.tz/g and app.tb/late, which files after it
  ;; define), and tests calling and naming them. The whole tree's run of the same property cost 6.7 G ticks, an
  ;; eighth of it 1.5 G, and each import of a store of its own about 100 M ticks beside the sample's.
  (let [here (seon.config/checkout)
        dirs (cfg/value :seon.config.source/dirs)
        tiny-tree (fn [label]
                    (checkout! label
                               (into {"deps.edn" "{}"
                                      "src/app/ta.clj" "(ns app.ta (:require [app.tz :as z]))\n(defn f [x] (z/g x))\n(def names '[app.tz/g app.tb/late])\n"
                                      "src/app/tz.clj" "(ns app.tz)\n(defn g [x] (inc x))\n"
                                      "src/app/tb.clj" "(ns app.tb)\n(defn late [x] x)\n"}
                                     (for [i (range 4)]
                                       [(str "test/app/t" i "_test.clj")
                                        (str "(ns app.t" i "-test (:require [clojure.test :refer [deftest is]] [app.ta :as ta]))\n"
                                             "(deftest calls-test (is (= 2 (ta/f 1))))\n"
                                             "(deftest names-test (is (= '[app.tb/late] ['app.tb/late])))\n")]))))
        copy (tiny-tree "copy") copy2 (tiny-tree "copy2")
        in (fx/inputs here)
        a (ts/reach-digests (runner/program-db copy (ts/program-store)) in)
        b (ts/reach-digests (runner/program-db copy (ts/program-store)) in)
        ;; another checkout, in a store of its own: imported again, not read from a's branch
        c (ts/reach-digests (runner/program-db copy2 (scratch-dir "store2")) in)
        ;; root imports into its own store, which holds no example program (the test store's seed
        ;; does: names a test quotes would reach it)
        {:keys [conn]} (pdb/open! :lmdb (scratch-dir "root-import") 0 :db)
        r (try (d/transact conn {:tx-data (source/import-tx @conn (:value (fx/read-source {:req {:root copy :dirs dirs}})))})
               (ts/reach-digests @conn in)
               (finally (d/release conn true)))]
    (is (< 5 (count a)) "a sample of the suite, not an empty tree")
    (is (= a b) "the same tree imported twice in one process: every digest equal")
    (is (= a c) "the same tree in another checkout: every digest equal, so its results are shared")
    (is (= a (select-keys r (keys a))) "root's import of the tree: bin/test's digest for every test")
    (with-dir
     (fn [rconn]
       (record-ran! rconn (into {} (for [[t dg] r :when (a t)] [t {:test t :digest dg :pass 1 :fail 0 :error 0}])))
       (is (= [] (:run (run-plan a rconn))) "with each green root's run stored, bin/test's plan of the tree runs nothing")))))

(deftest a-test-digest-takes-only-the-config-keys-it-reads
  ;; every digest took the whole config in force (seon.fx/config-input), so a key no test reads
  ;; (an agent's max-running a root was --set with) moved all 917 and none was reused: a test's
  ;; digest takes the values of the keys its reach names (seon.program/config-reads)
  (let [here (seon.config/checkout)
        g (kept-graph here)
        in (fx/inputs here)
        digests #(update-vals (program/graph-index g % nil) :digest)
        a (digests in)
        moved (fn [k v] (let [b (digests (assoc-in in [:config k] v))] (set (for [[t d] a :when (not= d (b t))] t))))
        ;; a key read by a computed keyword: seon.render/pattern's (config/value lim), lim a join's
        ;; :limit that a schema entry's :seon.ctx/pattern names (seon.program/computed-config-reader?, by its facts);
        ;; without it all 69 tests reaching pattern kept their digest when the window moved
        window (moved :seon.config.ctx/window "7")
        readers (program/tests-reaching g 'seon.render/pattern)]
    (is (< 0 (count window) (quot (count a) 2)) (str (count window) " of " (count a) " tests read :seon.config.ctx/window: some, fewer than half"))
    (is (not (window 'seon.reader-test/prose-without-a-fence-is-a-comment)) "a test that reaches no reader of the key keeps its digest")
    ;; root, 2026-10-27: a form taking the whole config map (root's boot, from-db) took every key,
    ;; so each new key moved the 57 tests that boot a root; it reads the keys it names
    (is (= #{} (moved (keyword "seon.config.no" "such-key") "1")) "a key no form names moves no test, a root-booting one included")
    (is (seq readers) "tests reach the reader of a computed key")
    (is (true? (#'program/computed-config-reader? '{:ns seon.render :refs #{seon.config/value} :keywords #{:limit}}))
        "a resolver call naming no key of its own reads a computed one")
    (is (false? (#'program/computed-config-reader? '{:ns seon.render :refs #{seon.config/value} :keywords #{:seon.config.ctx/window}}))
        "one naming its key reads that key")
    (is (false? (#'program/computed-config-reader? '{:ns seon.config :refs #{seon.config/resolve} :keywords #{}})) "seon.config's own readers name none")
    (is (= [] (remove window readers)) "a key read through a computed keyword moves every test reaching its reader, found by its facts")))

(deftest no-test-takes-the-tree-in-its-digest
  ;; owner, 2026-10-27: "no tests should include all of src". A test that reads files (a test of the
  ;; docs, of the task files) or runs the binary was digested by the whole tree, so any edit reran its
  ;; 286; it is digested by what it reaches, like any other test, and a file or a binary outside its
  ;; reach is the full run's to catch (bin/test --all, docs/merging.md). A check step still takes
  ;; the tree when what it reaches reads it, and a test reading the checkout's files from its own
  ;; namespace (seon.program/file-readers) does.
  (let [g (kept-graph (seon.config/checkout)) ;; the graph once: each digest set is an index over it
        in #(hash-map :env "e" :tree % :config {})
        digests (fn [inputs roots] (update-vals (program/graph-index g inputs roots) :digest))
        a (digests (in "t1") nil)
        b (digests (in "t2") nil)
        step #(digests (in %) {"step" ['seon.task-files-test/the-shipped-task-files-load]})]
    (is (< 20 (count a)) "a sample of the suite, not an empty tree")
    ;; the one exception: a test whose test namespace walks a directory (file-seq) judges the files
    ;; themselves, so it takes the tree; no-text-uses-an-old-word judged a red tree and a green one by
    ;; one digest and was marked flaky (the-old-word-check-reads, 2026-10-27)
    (let [moved (set (for [[t d] a :when (not= d (b t))] t))]
      (is (contains? moved 'seon.doc-test/no-text-uses-an-old-word) "a scan of the checkout takes the tree")
      ;; and so does a test calling a file reader from its own namespace (slurp, seon.doc/load-dir-tx): each was reused over an edit to the files it judges (web-ui, 2026-10-28)
      (is (contains? moved 'seon.render-test/one-printer-and-no-cut-before-it) "a test slurping a doc takes the tree")
      (is (contains? moved 'seon.doc-test/every-section-of-the-wiki-has-a-lead) "a test loading the wiki takes the tree")
      (is (contains? moved 'seon.repl-test/the-help-page-runs) "a test reading the wiki through seon.doc/pages takes the tree (it was reused over edits to the help page)")
      (is (contains? moved 'seon.task-files-test/no-shipped-task-is-merged-work) "and one reading the task files through my.root/task-files")
      (is (< (* 4 (count moved)) (count a)) (str (count moved) " of " (count a) " tests read the checkout's files from their own namespace: a few, not the 286 whose reach reads one"))
      (is (not-any? #(= "seon.root-test" (namespace %)) moved) "a test running the binary takes no tree"))
    (is (not= (step "t1") (step "t2")) "a step reaching a tree reader still takes the tree")))

(deftest a-test-naming-another-test-does-not-reach-it
  ;; seon.agent-test's tests quote the proofs they ask for: data, never a call, so a change the
  ;; named test reaches selects it alone (with quoted names by namespace, the naming tests
  ;; selected themselves and booted roots in the agent's test fork)
  (let [db (record (calc-db) 'user "(ns app.pt-names-test (:require [clojure.test :refer [deftest is]]))"
                   'app.pt-names-test "(deftest names-a-proof (is (= 'app.pt-calc-test/add-test 'app.pt-calc-test/add-test)))")
        db' (record db 'app.pt-calc "(defn add [a b] (+ b a))")
        k 'app.pt-names-test/names-a-proof
        before (ts/reach-digests db {:env "e" :tree "t" :config {}})
        after (ts/reach-digests db' {:env "e" :tree "t" :config {}})]
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
        in {:env "e" :tree "t" :config {} :data-refs '{app.pt-sys/read-decl #{app.pt-procs/writer-proc}}}
        k 'app.pt-sys-test/boot-test]
    (is (not= ((ts/reach-digests db in) k) ((ts/reach-digests db' in) k)) "the proc changed: the test booting through the declaration moves")
    (is (contains? (get (fx/declared-refs (str (seon.config/checkout) "/resources/seon/system.edn")) 'seon.system/read-decl) 'seon.writer/writer-proc)
        "the shipped declaration's procs are the reader's data refs")))

(deftest the-config-in-force-is-an-input
  ;; config/local.edn is gitignored and #env values are no files: a result proven under one
  ;; checkout's config was reused in another's
  (let [here (seon.config/checkout)
        cfg {:seon.config.ai/model "a" :seon.config.root/runs-dir (str here "/runs") :seon.config.web/port 8451
             :seon.config.native/probe-lib (str here "/target/lib/libprobe.dylib")}
        config #(:config (fx/inputs here % here))]
    (is (= "\"a\"" (:seon.config.ai/model (config cfg))) "a value in force is an input, for the tests that read its key")
    ;; owner, 2026-10-23: agents' greens and a checkout's are one store, so where a process
    ;; runs (its :seon.config/place keys) is no input; how a test behaves still is
    (is (= (config cfg) (config (assoc cfg :seon.config.web/port 9999 :seon.config.root/main "in-agent-1" :seon.config.root/store-path "/x/store"
                                       :seon.config.source/root here :seon.config.source/dirs ["src" "test"]
                                      )))
        "a checkout's own ports, main, store and source are no input")
    (is (= (config cfg) (:config (fx/inputs here (assoc cfg :seon.config.native/probe-lib "/elsewhere/target/lib/libprobe.dylib") "/elsewhere")))
        "a path under the checkout is the same input in any checkout")
    (is (not= (config cfg) (config (assoc cfg :seon.config.native/probe-lib "/elsewhere/target/lib/libprobe.dylib")))
        "a path outside it is another input")
    (is (not= (:env (fx/inputs here cfg here)) (:env (fx/inputs here (assoc cfg :seon.config.agent/test-ticks 7) here)))
        "the tick bound every verdict is a function of is every test's input")
    (is (= (:env (fx/inputs here cfg here)) (:env (fx/inputs here (assoc cfg :seon.config.ai/model "b") here)))
        "any other key is only the input of the tests that read it (a-test-digest-takes-only-the-config-keys-it-reads)")))

(deftest an-eval-that-changes-a-contract-or-docstring-says-so
  ;; owner, 2026-10-25 (DECISIONS: a change to a schema is an explicit fact the eval shows): what an
  ;; eval's definitions change of an existing var's contract or docstring, read from its own tx-data,
  ;; with the wiki sections citing the var and the form that reads each
  (let [db0 (ts/test-db)
        db (d/db-with db0 [{:db/id "f" :var/name 'app/f :var/doc "Old words." :var/contract "[:=> [:cat] :int]"}
                           {:db/id "g" :var/name 'app/g :var/doc "Kept."}])
        db (d/db-with db (seon.doc/load-tx db "docs" {"docs/a.md" "# A\n\nIt reads `app/f`.\n"}))
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

(defn- chain-account
  "Form i of a chain app.pc/f0..: f i calls f i-1; a test t names the one form it calls."
  [i body]
  {:id (str "app.pc/f" i "-" body) :src (str "(defn f" i " [x] " body ")") :ns 'app.pc :ns-form? false
   :defs [{:var (symbol "app.pc" (str "f" i)) :kind :fn}] :declares #{} :methods [] :impls []
   :refs (if (pos? i) #{(symbol "app.pc" (str "f" (dec i)))} #{}) :requires #{} :banned #{} :inline-defs #{}})

(deftest the-tests-reaching-a-var-are-what-its-change-selects
  ;; root's ruling (audit 2, item 2): (my.agent/tests-reaching 'v) answers from the reach graph by
  ;; the one reverse walk a change re-folds by (seon.program/reached-by), so its tests are exactly
  ;; those whose reach digest moves when v changes, the tests prove then runs; nearest first
  (let [test-acct (fn [t i] {:id (str "app.pc-test/" t) :src (str "(deftest " t " (f" i " 1))") :ns 'app.pc-test :ns-form? false
                             :defs [{:var (symbol "app.pc-test" t) :kind :test}] :declares #{} :methods [] :impls []
                             :refs #{(symbol "app.pc" (str "f" i))} :requires #{} :banned #{} :inline-defs #{}})
        db (ts/test-db)
        db (d/db-with db (vec (concat (mapcat #(program/account-tx db (chain-account % "x") %) (range 10))
                                      (mapcat (fn [[t i] k] (program/account-tx db (test-acct t i) (+ 10 k))) [["ta" 2] ["tb" 7] ["tc" 9]] (range)))))
        g (program/reach-graph db nil)
        in {:env "e" :tree "t" :config {}}
        moved (fn [i] (let [db2 (d/db-with db (program/record-tx db (chain-account i "(dec x)")))
                            before (program/graph-index g in nil) after (program/graph-index (program/reach-update g db2) in nil)]
                        (set (for [[t {d :digest}] after :when (not= d (:digest (before t)))] t))))]
    (doseq [i [2 5 9]]
      (is (= (moved i) (set (program/tests-reaching g (symbol "app.pc" (str "f" i))))) (str "f" i ": the tests its change selects")))
    (is (= '[app.pc-test/tb app.pc-test/tc] (program/tests-reaching g 'app.pc/f5)) "nearest first: tb calls f7, tc f9")
    (is (= '[app.pc-test/ta app.pc-test/tb app.pc-test/tc] (program/tests-reaching g 'app.pc/f0)) "every test reaches f0")))

(deftest a-program-with-no-schema-change-declares-nothing-new
  ;; THE PLAN 0.7's first merge through Seon (2026-10-27): (my.branch/merge-main!) on a branch whose
  ;; merge carried no schema change failed with seon.schema.datahike/declarations invalid-input {}:
  ;; schema-registry answered {} for "nothing changed", and seon.merge handed that to
  ;; schema-declarations. Nothing changed is the built registry itself
  (let [db (ts/test-db)]
    (is (= [] (program/schema-declarations db (program/schema-registry {}))) "the built schema, all declared already")
    (is (identical? seon.contract/registry (program/schema-registry {})))))

