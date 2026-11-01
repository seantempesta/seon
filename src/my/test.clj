(ns my.test
  "Testing in Seon's world (Seon's my.test): a test runs on a small world shaped like production,
  plus what it adds, as a db value; no store, no import of the real program. The default:
  (my.test/with (my.test/world) (my.test/code 'app.x \"(ns app.x) (defn f [x] (inc x))\")). The parts
  compose: `world` is a db value and every builder answers a fixture, so a test can build any db
  it needs with them. See docs/testing.md#testing-in-the-world."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.core] [datahike.api :as d] [seon.db :as pdb]
            [seon.source :as source] [seon.doc :as doc] [seon.task :as task] [seon.profile :as profile]))

(defn- empty-db
  "An empty db value with Seon's schema (seon.db/schema), in memory, keeping history when history?. See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :boolean] :seon.host/db]}
  [history?] (datahike.core/empty-db (into {} (map (juxt :db/ident #(dissoc % :db/ident))) pdb/schema)
                                     {:keep-history? history? :schema-flexibility :write}))

(defn with
  "db with each fixture applied, in order: a fixture is tx-data, or a function of the db answering
  tx-data (what `code`, `page` and `task` answer). A value, nothing written.
  Example: (my.test/with (my.test/world) (my.test/page \"docs/y.md\" \"# Y\\n\\nSee app.calc/add.\")) See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :seon.host/db [:* :any]] :seon.host/db]}
  [db & fixtures]
  (reduce (fn [db fx] (datahike.core/db-with db (if (fn? fx) (fx db) fx))) db fixtures))

(defn code
  "A fixture: namespace nsym's source text as program facts (forms, vars, their contracts and
  references), as an import of its file records it (seon.source/text-tx): under test/ when nsym ends
  in -test, else under src/.
  Example: (my.test/code 'app.x \"(ns app.x)\\n(defn f [x] (inc x))\") See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :symbol :string] :seon.host/fn]}
  [nsym text]
  (fn [db] (source/text-tx db nsym text (str (if (.endsWith (str nsym) "-test") "test/" "src/")
                                              (-> (str nsym) (.replace "-" "_") (.replace "." "/")) ".clj"))))

(defn page
  "A fixture: a wiki page at path (under docs/) with markdown text, as its sections and their
  citations (seon.doc/load-tx). Example: (my.test/page \"docs/x.md\" \"# X\\n\\nSee app.calc/add.\") See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :string :string] :seon.host/fn]}
  [path text]
  (fn [db] (doc/load-tx db "docs" {path text})))

(defn task
  "A fixture: a held task named nsym/tname with its plan and proofs, and the tasks it needs, by id
  (\"ns/name\": a task of the same `with` or one the db holds) (seon.task/create-tx).
  Example: (my.test/task 'app.x \"fix-f\" \"Make app.x/f add two.\" #{'app.x-test/f-adds-two} #{\"app.calc/total-is-exact\"}) See docs/testing.md#testing-in-the-world."
  {:malli/schema [:function [:=> [:cat :symbol :string :string] :seon.host/fn]
                  [:=> [:cat :symbol :string :string [:set :symbol]] :seon.host/fn]
                  [:=> [:cat :symbol :string :string [:set :symbol] [:set :string]] :seon.host/fn]]}
  ([nsym tname plan] (task nsym tname plan #{} #{}))
  ([nsym tname plan proofs] (task nsym tname plan proofs #{}))
  ([nsym tname plan proofs needs]
   (fn [db] (task/create-tx db [(cond-> {:seon.task/ns nsym :seon.task/name tname :seon.task/plan plan
                                         :seon.task/kind :work :seon.task/proofs proofs}
                                  (seq needs) (assoc :seon.task/needs needs))]))))

(defn without
  "A fixture: the world without var sym (its var and the form that defines it retracted), to test
  what a missing or deleted definition does. Example: (my.test/with (my.test/world) (my.test/without 'app.calc/inc2)) See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :qualified-symbol] :seon.host/fn]}
  [sym]
  (fn [db] (let [v (d/entity db [:var/name sym])]
             (cond-> []
               v (conj [:db/retractEntity (:db/id v)])
               (:var/form v) (conj [:db/retractEntity (:db/id (:var/form v))])))))

(def ^:private base
  "The world's contents, as fixtures: a program of two namespaces (references between vars, a
  contract, a macro), its test namespace, a wiki page citing them, and a task. See docs/testing.md#testing-in-the-world."
  [(code 'app.calc (str "(ns app.calc \"Arithmetic the examples use.\")\n\n"
                        "(defmacro twice \"Evaluates body twice, answering the second.\" [& body] `(do ~@body ~@body))\n\n"
                        "(defn add \"x plus y. See docs/calc.md#calc.\" {:malli/schema [:=> [:cat :int :int] :int]} [x y] (+ x y))\n\n"
                        "(defn inc2 \"x plus two.\" [x] (add x 2))\n\n"
                        "(defn total \"The sum of xs.\" [xs] (reduce add 0 xs))\n"))
   (code 'app.report (str "(ns app.report \"Reports over app.calc.\" (:require [app.calc :as calc]))\n\n"
                          "(defn line \"One line naming n and its total.\" [n xs] (str n \": \" (calc/total xs)))\n"))
   (code 'app.calc-test (str "(ns app.calc-test (:require [clojure.test :refer [deftest is]] [app.calc :as calc]))\n\n"
                             "(deftest add-adds (is (= 3 (calc/add 1 2))))\n\n"
                             "(deftest total-sums (is (= 6 (calc/total [1 2 3]))))\n"))
   (page "docs/calc.md" "# Calc\n\nThe arithmetic every example uses: app.calc/add, app.calc/total.\n\n## Reports\n\nA report line, app.report/line, totals with app.calc/total.\n")
   (task 'app.calc "total-is-exact" "Make app.calc/total exact for an empty seq (0)." #{'app.calc-test/total-sums})])

(def ^:private the-worlds
  "The world and the world that keeps history, each built once a process from base (`world` answers them). See docs/testing.md#testing-in-the-world."
  {false (delay (apply with (empty-db false) base)) true (delay (apply with (empty-db true) base))})

(defn world
  "The base world, a db value: Seon's schema and a small program shaped like production:
  namespaces app.calc (add, inc2, total; a macro twice; add contracted) and app.report (line,
  calling app.calc/total), their tests app.calc-test (add-adds, total-sums), the wiki page
  docs/calc.md citing them, and the task app.calc/total-is-exact. Built once per process, in
  memory (~0.6 M ticks). Add what a test needs with `with`. With {:history? true}, the same world
  on a db that keeps history, for a test that reads d/history or d/as-of.
  Example: (my.test/world {:history? true}) See docs/testing.md#testing-in-the-world."
  {:malli/schema [:function [:=> [:cat] :seon.host/db] [:=> [:cat [:map [:history? {:optional true} :boolean]]] :seon.host/db]]}
  ([] (world {}))
  ([{:keys [history?]}] @(the-worlds (boolean history?))))

(defn cost
  "The work (thunk) does: {:ticks :bytes :ms :value}, the engine ticks and bytes the test thread
  spent (seon.profile/measure!), what every test is judged by (its ceiling is
  :seon.config.agent/test-ticks). Example: (:ticks (my.test/cost #(app.calc/total (range 100)))) See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :seon.host/fn] :map]}
  [thunk]
  (let [v (volatile! nil) r (volatile! nil)]
    (profile/measure! #(vreset! r (thunk)) (fn [d] (vreset! v d)))
    {:ticks (:ticks @v) :bytes (:bytes @v) :ms (:ms @v) :value @r}))
