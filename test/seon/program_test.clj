(ns seon.program-test
  "The program as a REPL session leaves it (next-review #6 and #5, owner 2026-10-20: \"We
  should be able to create new namespaces period. Just like a REPL.\"): a new namespace is
  program with its ns form as written; `user` and ^:scratch namespaces are not; a def is
  kept as the exact text written with its var's metadata; a staged plain def keeps its
  quoting and metadata; main loads namespaces and definitions in dependency order; a test
  that names its subject through a :refer reaches it."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.merge :as pm]
            [seon.program :as program]
            [seon.harvest :as harvest]))

(defn- empty-db [] @(second (pdb/test-branch!)))

(defn- ev
  "Evaluate src (one form) as the REPL does, in namespace nsym: [form value]."
  [nsym src]
  (let [form (binding [*ns* (program/ensure-ns! nsym)] (read-string src))]
    [form (binding [*ns* (program/ensure-ns! nsym)] (eval form))]))

(defn- record
  "What evaluating src in nsym records on db, as the agent's wrap-record does: [what tx] or nil."
  [db nsym src]
  (let [[form v] (ev nsym src)] (program/recording db form src v)))

(deftest a-new-namespace-is-program-with-its-ns-form-as-written
  (let [db (empty-db)
        src "(ns app.pt-new\n  (:require [clojure.string :as s :refer [join]]))"
        [what tx] (record db 'user src)
        db (d/db-with db tx)]
    (is (= 'app.pt-new what))
    (is (= src (d/q '[:find ?s . :where [?e :ns/name app.pt-new] [?e :ns/src ?s]] db)) "the ns form, byte for byte")
    (testing "a def in it is recorded, with no namespace declared beforehand"
      (let [[what tx] (record db 'app.pt-new "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x]  (* 2 x))")
            db (d/db-with db tx)]
        (is (= 'app.pt-new/twice what))
        (is (= "(defn twice {:malli/schema [:=> [:cat :int] :int]} [x]  (* 2 x))"
               (d/q '[:find ?s . :where [?e :var/name app.pt-new/twice] [?e :var/src ?s]] db)))))
    (testing "in-ns makes a namespace new to the branch program"
      (is (= 'app.pt-other (first (record db 'user "(in-ns 'app.pt-other)"))))
      (is (nil? (record db 'user "(in-ns 'app.pt-new)")) "already program: nothing new happened"))))

(deftest user-and-scratch-namespaces-stay-scratch
  (let [db (empty-db)]
    (is (nil? (record db 'user "(def scratch-x 1)")))
    (is (nil? (record db 'user "(ns ^:scratch app.pt-scratch)")))
    (is (nil? (record db 'app.pt-scratch "(defn h [x] x)")) "a def in a ^:scratch namespace")
    (let [own (create-ns 'agent.pt-a1)]
      (alter-meta! own assoc :scratch true)
      (is (nil? (record db 'agent.pt-a1 "(def y 2)")) "the agent's own namespace"))))

(deftest a-def-is-kept-as-written-with-its-metadata
  (let [db (empty-db)
        tx-of (fn [src] (second (record db 'app.pt-meta src)))
        fact (fn [tx a] (some a (filter map? tx)))]
    (is (= "(def ^:dynamic *depth* 0)" (fact (tx-of "(def ^:dynamic *depth* 0)") :var/src)))
    (is (= "{:dynamic true}" (fact (tx-of "(def ^:dynamic *depth* 0)") :var/meta)))
    (is (= "{:private true}" (fact (tx-of "(defn- h {:malli/schema [:=> [:cat :int] :int]} [x] x)") :var/meta)))
    (is (= "(def ops '(inc 1))" (fact (tx-of "(def ops '(inc 1))") :var/src)))
    (is (= "Doubles." (fact (tx-of "(defn d \"Doubles.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))") :var/doc)))))

(deftest a-staged-plain-def-keeps-its-quoting-and-metadata
  ;; each staged source loads in a fresh namespace and means what the branch's var held
  (let [load (fn [nsym src] (program/load-src! nsym src) (ns-resolve nsym (symbol (second (re-find #"\(def (?:\^\{[^}]*\} )?(\S+)" src)))))]
    (testing "a quoted list stays a list, quoted symbols stay symbols"
      (let [[_ v] (ev 'app.pt-vals "(def ops '(inc 1))")
            [_ w] (ev 'app.pt-vals "(def syms '[a b])")]
        (is (= "(def ops '(inc 1))" (harvest/value-src v "(def ops '(inc 1))")) "kept as written")
        (is (= '(inc 1) @(load 'app.pt-vals2 (harvest/value-src v nil))))
        (is (= '[a b] @(load 'app.pt-vals2 (harvest/value-src w nil))))))
    (testing "an effectful init merges as its value, quoted, its metadata kept"
      (let [[_ v] (ev 'app.pt-vals "(def ^:dynamic *x* (do (inc 1)))")
            src (harvest/value-src v "(def ^:dynamic *x* (do (inc 1)))")
            v2 (load 'app.pt-vals3 src)]
        (is (= "(def ^{:dynamic true} *x* '2)" src))
        (is (= 2 @v2))
        (is (:dynamic (meta v2)))
        (is (= 3 (eval (list 'binding [(symbol v2) 3] (symbol v2)))) "binding works on main")))))

(defn- program-db
  "A db holding namespaces {nsym ns-src-or-nil} and definitions [[sym src] ...]."
  [nss defs]
  (d/db-with (empty-db)
             (into (vec (for [[n src] nss] (cond-> {:db/id (str "ns " n) :ns/name n} src (assoc :ns/src src))))
                   (for [[v src] defs] {:var/name v :var/ns (str "ns " (namespace v)) :var/src src}))))

(def ^:private calc
  (program-db {'app.pt-calc nil
               'app.pt-calc-test "(ns app.pt-calc-test (:require [clojure.test :refer [deftest is]] [app.pt-calc :refer [add]]))"}
              [['app.pt-calc/sum3 "(defn sum3 [a b c] (add a (add b c)))"]
               ['app.pt-calc/add "(defn add [a b] (+ a b))"]
               ['app.pt-calc/neg "(defn neg [x] (- x))"]
               ['app.pt-calc-test/add-test "(deftest add-test (is (= 3 (add 1 2))))"]
               ['app.pt-calc-test/sum3-test "(deftest sum3-test (is (= 6 (app.pt-calc/sum3 1 2 3))))"]]))

(deftest main-loads-in-dependency-order
  (let [steps (program/load-order calc '#{app.pt-calc app.pt-calc-test}
                                  (vec (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/src ?s]] calc)))
        at (fn [x] (.indexOf (mapv second steps) x))]
    (is (< (at 'app.pt-calc) (at 'app.pt-calc-test)) "a namespace after the namespaces its ns form requires")
    (is (< (at 'app.pt-calc/add) (at 'app.pt-calc/sum3)) "a definition after the definitions it names (sum3 sorts first)")
    (is (< (at 'app.pt-calc/sum3) (at 'app.pt-calc-test/add-test)) "tests after every other definition")
    (is (= [] (:errors (program/load-all! calc))) "it loads: the test namespace's :refer finds add")
    (is (= {:test 2 :pass 2 :fail 0 :error 0}
           (select-keys (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.pt-calc-test)) [:test :pass :fail :error])))))

(deftest a-test-reaches-what-it-refers
  (is (= '#{app.pt-calc-test/add-test app.pt-calc-test/sum3-test} (program/reaching-tests calc '#{app.pt-calc/add}))
      "add-test names add through its :refer; sum3-test reaches add through sum3")
  (is (= '#{} (program/reaching-tests calc '#{app.pt-calc/neg}))))

(deftest the-ns-form-merges-to-main
  (let [base (program-db {'app.pt-m nil} [])
        ours (d/db-with base [{:db/id "n" :ns/name 'app.pt-m2 :ns/src "(ns app.pt-m2 (:require [clojure.set :as set]))"}])
        r (pm/three-way (pm/program base #{}) (pm/program ours #{}) (pm/program base #{}))
        main' (d/db-with base (pm/apply-tx base (into {} (for [[k [_ o]] (:ours r)] [k o]))))]
    (is (= [[:ns 'app.pt-m2]] (keys (:ours r))))
    (is (= "(ns app.pt-m2 (:require [clojure.set :as set]))" (d/q '[:find ?s . :where [?e :ns/name app.pt-m2] [?e :ns/src ?s]] main')))))
