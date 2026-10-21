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
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.program :as program]
            [seon.merge :as pm]
            [seon.source :as source]
            [jolt.analyzer]))

(defn- empty-db [] @(second (pdb/test-branch!)))

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
    (program/account (assoc @acc :src src :ns nsym :ns-after after :ns-form? (ns-form? form nsx) :ran? true
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
        (is (= '#{clojure.string/join clojure.core/*} (set (d/q '[:find [?r ...] :where [?v :var/name app.pt-new/twice] [?v :var/form ?f] [?f :form/refs ?r]] db)))
            "s/join resolved through the alias, * to clojure.core's")))
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

(deftest catches-and-clocks-are-recorded
  ;; for the fence detectors (lane PATTERNS): a hiding guard is a catch answering a constant;
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

(deftest a-test-reaches-what-it-calls
  (let [db (apply record (empty-db) calc-srcs)]
    (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test} (program/reaching-tests db '#{app.pt-calc/add}))
        "add-test calls add through its :refer; sum3-test reaches add through sum3")
    (is (= '#{} (program/reaching-tests db '#{app.pt-calc/neg})))))

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
      (is (= '#{clojure.string/join clojure.core/defmethod-setup} (:refs (by-src "(defmethod m :a [_] (s/join [\"a\"]))"))))
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
        main (d/db-with base (pm/apply-tx base (into {} (for [[k [_ o]] (:ours r)] [k o]))))]
    (is (= '#{[:ns app.pt-mm] [:var app.pt-mm/area] [:method app.pt-mm/area ":square"] [:method app.pt-mm/area ":circle"]
              [:var app.pt-mm/Named] [:var app.pt-mm/label] [:impl app.pt-mm/Named "String"]
              [:var app.pt-mm/Circle] [:var app.pt-mm/->Circle] [:var app.pt-mm/map->Circle]}
           (set (remove #(= :form (first %)) (keys (:ours r)))))
        "each definition is its own merge key: methods [multi dispatch], extensions [protocol type]")
    (is (= [] (pm/unmerged main (pm/program main #{}))))
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
