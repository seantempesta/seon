(ns seon.cst-metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.code.cst :as cst]
            [seon.code.cst.ns :as cstns]
            [seon.health.metrics :as m]
            [seon.code.parse :as parse]
            [seon.health.contracts :as contracts]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.render.entities.work :as rwork]
            [seon.render.links :as rlinks]
            [seon.render.wire :as rwire]
            [seon.test-fixture :as tf]))

(deftest patch-form-source-test
  (testing "single + round-trip preserves unrelated source"
    (let [src "(defn f [x]   (+ x 1)) ; keep"
          r (cst/patch-form-source src "(+ x 1)" "(* x 2)")]
      (is (:ok? r))
      (is (= "(defn f [x]   (* x 2)) ; keep" (:src r)))
      (is (= 1 (:applied r)))))
  (testing "no match reports :no-match, not silent"
    (let [r (cst/patch-form-source "(defn f [x] (+ x 1))" "(missing)" "z")]
      (is (false? (:ok? r)))
      (is (= :no-match (:error r)))
      (is (= 0 (:applied r)))))
  (testing "empty src reports :no-match"
    (let [r (cst/patch-form-source "" "a" "b")]
      (is (false? (:ok? r)))
      (is (= :no-match (:error r)))))
  (testing "invalid syntax reported at parse stage"
    (let [r (cst/patch-form-source "(defn f [x] (+ x 1)" "a" "b")]
      (is (false? (:ok? r)))
      (is (= :invalid-clojure-syntax (:error r))))))

(deftest patch-form-source-batch-test
  (testing "applies multiple edits in one pass"
    (let [r (cst/patch-form-source-batch "(defn f [x] [(+ x 1) (* x 2)])"
                                         [["(+ x 1)" "(+ x 10)"] ["(* x 2)" "(* x 20)"]])]
      (is (:ok? r))
      (is (= 2 (:applied r)))
      (is (= "(defn f [x] [(+ x 10) (* x 20)])" (:src r)))))
  (testing "empty pair list is a trivial no-op"
    (let [r (cst/patch-form-source-batch "(defn f [x] 1)" [])]
      (is (:ok? r))
      (is (= 0 (:applied r)))
      (is (= "(defn f [x] 1)" (:src r)))))
  (testing "ambiguous duplicate must be reported, not applied"
    (let [r (cst/patch-form-source-batch "(do (+ 1 2) (+ 1 2))" [["(+ 1 2)" "(+ 3 4)"]])]
      (is (false? (:ok? r)))
      (is (= :ambiguous-match (:error r)))
      (is (= 2 (:count r)))
      (is (= 0 (:applied r))))))

(deftest slice-edit-test
  (testing "contiguous sibling slice edit preserves surrounding source"
    (let [src "(defn f [x] (let [a 1 b 2] (+ a b)))"
          r (cst/patch-form-source src "[a 1 b 2]" "[a 10 b 20]" {:occurrence 1})]
      (is (:ok? r))
      (is (= "(defn f [x] (let [a 10 b 20] (+ a b)))" (:src r)))))
  (testing "slice no-match is reported"
    (let [r (cst/patch-form-source "(defn f [] (let [a 1] a))" "[a 2 b 3]" "[z 9]")]
      (is (false? (:ok? r)))
      (is (= :no-match (:error r))))))

(deftest prune-unused-requires-src-test
  (testing "removes genuinely unused lib, keeps used one"
    (let [src "(ns foo (:require [clojure.string :as str]\n            [clojure.set :as set]))\n(defn f [x] (str/upper-case x))"
          r (cstns/prune-unused-requires-src src)]
      (is (:ok? r))
      (is (:changed? r))
      (is (= ["clojure.set"] (:removed r)))
      (is (= ["clojure.string"] (:kept r)))
      (is (not (clojure.string/includes? (:src r) "clojure.set")))))
  (testing "all requires used => unchanged no-op"
    (let [src "(ns foo (:require [clojure.string :as str]))\n(defn f [x] (str/trim x))"
          r (cstns/prune-unused-requires-src src)]
      (is (:ok? r))
      (is (false? (:changed? r)))
      (is (= src (:src r)))))
  (testing "no require clause => unchanged"
    (let [src "(ns foo)\n(defn g [] 1)"
          r (cstns/prune-unused-requires-src src)]
      (is (:ok? r))
      (is (false? (:changed? r)))
      (is (= [] (:removed r))))))

(deftest add-require-src-test
  (testing "adds a new require to an existing clause"
    (let [r (cstns/add-require-src "(ns foo (:require [clojure.string :as str]))" '[seon.store :as store])]
      (is (:ok? r))
      (is (:changed? r))
      (is (= ["seon.store"] (:added r)))
      (is (clojure.string/includes? (:src r) "[seon.store :as store]"))))
  (testing "idempotent: adding twice is a no-op"
    (let [once (:src (cstns/add-require-src "(ns foo (:require [clojure.string :as str]))" '[seon.store :as store]))
          twice (cstns/add-require-src once '[seon.store :as store])]
      (is (:ok? twice))
      (is (false? (:changed? twice)))
      (is (= [] (:added twice)))
      (is (= once (:src twice)))))
  (testing "adds require clause when absent"
    (let [r (cstns/add-require-src "(ns foo)\n(defn g [] 1)" '[seon.store :as store])]
      (is (:ok? r))
      (is (:changed? r))
      (is (clojure.string/includes? (:src r) "(:require [seon.store :as store])")))))

(deftest health-metrics-test
  (testing "classify-eval-error maps known signatures to stable classes"
    (is (= :alloc-budget-overrun (m/classify-eval-error "Eval exceeded allocation budget (limit 1 bytes).")))
    (is (= :tick-budget-overrun (m/classify-eval-error "exceeded tick budget")))
    (is (= :schema-contract-violation (m/classify-eval-error "Schema contract violation at ...")))
    (is (= :wrong-arity (m/classify-eval-error "Wrong number of args (2) passed to: user/callers")))
    (is (= :unresolved-var (m/classify-eval-error "Unable to resolve symbol: foo")))
    (is (= :reader-syntax (m/classify-eval-error "Reader syntax error at line 1")))
    (is (= :type-mismatch (m/classify-eval-error "String cannot be cast to Long")))
    (is (= :private-var (m/classify-eval-error "var is not public")))
    (is (= :other (m/classify-eval-error "totally unrecognized")))
    (is (= :other (m/classify-eval-error nil))))
  (testing "extract-tool-names finds user/* calls, then d/*, then raw fallbacks"
    (is (= ["source" "grep-src"] (m/extract-tool-names "(user/source 'a/b) (user/grep-src [\"x\"])")))
    (is (= ["d/q"] (m/extract-tool-names "(d/q '[:find ?e])")))
    (is (= ["d/datoms"] (m/extract-tool-names "(d/datoms db :eavt)")))
    (is (= ["raw-defn"] (m/extract-tool-names "(defn f [] 1)")))
    (is (= ["raw-deftest"] (m/extract-tool-names "(deftest x (is true))")))
    (is (= ["other-clj"] (m/extract-tool-names "(+ 1 2)"))))
  (testing "extract-tool-names dedupes repeated tools"
    (is (= ["source"] (m/extract-tool-names "(user/source 'a) (user/source 'b)"))))
  (testing "pct-val sorts input and clamps index"
    (is (= 3.0 (m/pct-val [1 2 3 4] 0.5)))
    (is (= 1.0 (m/pct-val [4 1 3 2] 0.0)))
    (is (= 4.0 (m/pct-val [4 1 3 2] 1.0)))
    (is (= 0.0 (m/pct-val [] 0.5)))
    (is (= 5.0 (m/pct-val [5] 1.0)))))

(deftest set-defn-meta-src-test
  (testing "inserts attr-map after name when absent"
    (let [r (cstns/set-defn-meta-src "(defn f [x] (+ x 1))" {:cost/ticks 100})]
      (is (:ok? r))
      (is (= :inserted (:action r)))
      (is (clojure.string/includes? (:src r) "{:cost/ticks 100}"))))
  (testing "inserts after docstring when present"
    (let [r (cstns/set-defn-meta-src "(defn f\n  \"doc\"\n  [x] (+ x 1))" {:cost/ticks 100})]
      (is (:ok? r))
      (is (= :inserted (:action r)))
      (is (clojure.string/includes? (:src r) "{:cost/ticks 100}"))))
  (testing "merges into existing attr-map rather than duplicating"
    (let [r (cstns/set-defn-meta-src "(defn f {:a 1} [x] 1)" {:cost/ticks 100})]
      (is (:ok? r))
      (is (= :merged (:action r)))
      (is (clojure.string/includes? (:src r) ":a 1"))
      (is (clojure.string/includes? (:src r) ":cost/ticks 100"))))
  (testing "idempotent merge: applying same meta twice equals once"
    (let [once (:src (cstns/set-defn-meta-src "(defn f {:a 1} [x] 1)" {:cost/ticks 100}))
          twice (cstns/set-defn-meta-src once {:cost/ticks 100})]
      (is (:ok? twice))
      (is (= :merged (:action twice)))
      (is (= once (:src twice)))))
  (testing "rejects empty meta map and non-defn forms"
    (is (= :invalid-meta-map (:error (cstns/set-defn-meta-src "(defn f [x] 1)" {}))))
    (is (= :not-a-defn-form (:error (cstns/set-defn-meta-src "(let [x 1] x)" {:a 1}))))))

(deftest
  malli-contract-validation-and-error-hints-test
  (let [h1 (parse/enrich-eval-error
             "(deftest x)"
             "Unable to resolve symbol: deftest"
             {})
        h2 (parse/enrich-eval-error
             "(f)"
             ":malli.core/invalid-schema"
             {})
        h3 (parse/enrich-eval-error
             "(clojure.test/run-tests 'foo)"
             "Eval exceeded allocation budget"
             {})]
    (is (clojure.string/includes? h1 "user/add-fn!"))
    (is (clojure.string/includes? h2 "number?"))
    (do
    (is (clojure.string/includes? h3 "user/run-tests!"))
    (is
      (clojure.string/includes?
        (parse/enrich-eval-error
          "(user/sh! \"grep \\[a\")"
          "Reader syntax error: Unsupported escape character: \\[."
          {})
        "double backslashes"))
    (is
      (clojure.string/includes?
        (parse/enrich-eval-error
          "(clojure.string/split-lines (user/fs-read \"/app/a\"))"
          "class clojure.lang.PersistentArrayMap cannot be cast to class java.lang.CharSequence"
          {})
        "(:content"))
    (is
      (clojure.string/includes?
        (parse/enrich-eval-error
          "(d/create-database {:store {:backend :memory :id \"x\"}})"
          "Store :id must be a UUID type. Got: class java.lang.String"
          {})
        "randomUUID"))
    (is
      (clojure.string/includes?
        (parse/enrich-eval-error
          "(deref seon.ctx/*conn*)"
          "No such var: seon.ctx/*conn*"
          {})
        "(user/db)"))))
  (let [cfg {:store {:backend :memory, :id (random-uuid)},
            :schema-flexibility :read,
            :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)]
    (try
      (d/transact
      conn
      {:tx-data
       [{:db/ident :seon.code.var/name,
         :db/cardinality :db.cardinality/one}
        {:db/ident :seon.code.var/ns, :db/cardinality :db.cardinality/one}
        {:db/ident :seon.code.var/schema,
         :db/cardinality :db.cardinality/one}
        {:seon.code.var/name 'demo/good-fn,
         :seon.code.var/ns 'demo,
         :seon.code.var/schema [:=> [:cat 'number? 'number?] :double]}
        {:seon.code.var/name 'demo/bad-fn,
         :seon.code.var/ns 'demo,
         :seon.code.var/schema [:=> [:cat :number :number] :double]}]})
      (let [probs (contracts/invalid-contract-violations @conn)]
        (is (= 1 (count probs)))
        (is (= :invalid-contract (:class (first probs))))
        (is (= 'demo/bad-fn (:var (first probs))))
        (is
          (clojure.string/includes?
            (:message (first probs))
            "number?")))
      (finally (d/release conn) (d/delete-database cfg)))))

(deftest
  apply-overlay-hydrates-unloaded-namespace-test
  (let [nsym (symbol
               (str
                 "seon.unloaded-overlay-test-"
                 (subs (str (random-uuid)) 0 8)))
        cfg {:store {:backend :memory, :id (random-uuid)},
             :schema-flexibility :read,
             :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)]
    (try
      (d/transact
        conn
        {:tx-data
         [{:db/ident :seon.code.form/key,
           :db/cardinality :db.cardinality/one}
          {:db/ident :seon.code.form/ns,
           :db/cardinality :db.cardinality/one,
           :db/index true}
          {:db/ident :seon.code.form/src,
           :db/cardinality :db.cardinality/one}
          {:db/ident :seon.code.form/order,
           :db/cardinality :db.cardinality/one}
          {:seon.code.form/key (str nsym "#ns"),
           :seon.code.form/ns nsym,
           :seon.code.form/order 0,
           :seon.code.form/src
           (str "(ns " nsym " (:require [clojure.string :as s]))")}
          {:seon.code.form/key (str nsym "/base-fn"),
           :seon.code.form/ns nsym,
           :seon.code.form/order 1,
           :seon.code.form/src
           "(defn base-fn [x] (s/upper-case x))"}]})
      (is (nil? (find-ns nsym)))
      (code/apply-overlay!
        @conn
        {:upserts
         [{:seon.code.form/key (str nsym "/derived-fn"),
           :seon.code.form/ns nsym,
           :seon.code.form/order 2,
           :seon.code.form/src
           "(defn derived-fn [x] (str (base-fn x) \"!\") )"}]})
      (is (some? (find-ns nsym)))
      (is (= "HI!" ((ns-resolve nsym 'derived-fn) "hi")))
      (finally
        (remove-ns nsym)
        (d/release conn)
        (d/delete-database cfg)))))

(deftest
  topo-order-ns-forms-and-source-line-args-test
  (testing
    "topo-order-ns-forms moves newly called helpers above callers with minimal diff"
    (let [forms [{:seon.code.form/key "demo#ns",
                  :seon.code.form/order 0,
                  :seon.code.form/deps #{}}
                 {:seon.code.form/key "demo/caller",
                  :seon.code.form/order 1,
                  :seon.code.form/deps
                  #{'demo/helper-a 'demo/helper-b}}
                 {:seon.code.form/key "demo/untouched",
                  :seon.code.form/order 2,
                  :seon.code.form/deps #{}}
                 {:seon.code.form/key "demo/helper-a",
                  :seon.code.form/order 3,
                  :seon.code.form/deps #{}}
                 {:seon.code.form/key "demo/helper-b",
                  :seon.code.form/order 4,
                  :seon.code.form/deps #{'demo/helper-a}}]
          updates (cstns/topo-order-ns-forms forms)
          ord-map (merge
                    (into
                      {}
                      (map
                        (juxt
                          :seon.code.form/key
                          :seon.code.form/order)
                        forms))
                    (into
                      {}
                      (map
                        (juxt
                          :seon.code.form/key
                          :seon.code.form/order)
                        updates)))]
      (is (= 0 (get ord-map "demo#ns")))
      (is
        (<
          (get ord-map "demo/helper-a")
          (get ord-map "demo/helper-b")))
      (is
        (<
          (get ord-map "demo/helper-b")
          (get ord-map "demo/caller")))))
  (testing
    "already-valid order produces zero updates"
    (let [forms [{:seon.code.form/key "demo#ns",
                  :seon.code.form/order 0,
                  :seon.code.form/deps #{}}
                 {:seon.code.form/key "demo/a",
                  :seon.code.form/order 1,
                  :seon.code.form/deps #{}}
                 {:seon.code.form/key "demo/b",
                  :seon.code.form/order 2,
                  :seon.code.form/deps #{'demo/a}}]]
      (is (= [] (cstns/topo-order-ns-forms forms))))))

(deftest ns-require-aliases-and-context-guidance-test
  (testing "ns-require-aliases extracts sorted :as/:refer specs and stays safe on nil/malformed input"
    (is (= ["[clojure.string :as str]" "[clojure.test :refer [deftest is]]" "[datahike.api :as d]"]
           (rlinks/ns-require-aliases "(ns demo.ns (:require [datahike.api :as d] [clojure.string :as str] [clojure.test :refer [deftest is]]))")))
    (is (= [] (rlinks/ns-require-aliases nil)))
    (is (= [] (rlinks/ns-require-aliases "")))
    (is (= [] (rlinks/ns-require-aliases "(ns broken ["))))
  (testing "render-ctx-snapshot includes general Clojure/Malli/test idioms guidance"
    (let [s (pr-str (:seon.ui/hiccup (rwork/render-ctx-snapshot {:seon.ctx/snapshot {:seon.agent/id :agent/demo :seon.agent/branch :branch/demo}})))]
      (is (clojure.string/includes? s "Idioms & traps:"))
      (is (clojure.string/includes? s "NEVER `:number`")))))

(deftest render-agent-context-parity-test
  (testing "render-agent-context renders <walk-context> (ctx, task, ns + Requires) and <ephemeral-tail>"
    (tf/with-test-store [{:keys [conn]}]
      (let [db0 @conn
            _ (d/transact conn {:tx-data (into [{:seon.agent/id :repl/demo :seon.agent/branch :branch/demo :seon.agent/role :repl}
                                                {:seon.task/id :seon.demo/t1 :seon.task/scope 'seon.demo
                                                 :seon.task/title "Demo task" :seon.task/plan "Do demo" :seon.task/done? false}]
                                               (code/file-import-tx nil "src/seon/demo.clj"
                                                 "(ns seon.demo (:require [clojure.string :as str]))\n(defn hi [x] (str/upper-case (str x)))\n" false))})
            db1 @conn
            out (rwire/render-agent-context db0 db1 :repl/demo {:task :seon.demo/t1})]
        (is (clojure.string/includes? out "<walk-context>"))
        (is (clojure.string/includes? out "(user/context"))
        (is (clojure.string/includes? out "Demo task"))
        (is (clojure.string/includes? out "Requires: [clojure.string :as str]"))
        (is (clojure.string/includes? out "<ephemeral-tail>"))))))