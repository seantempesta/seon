(ns seon.experimental.ns-instance-test
  "Tests for dynamic namespace instance isolation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.experimental.ns-instance :as ns-inst]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-base-ns* nil)
(def ^:dynamic *test-instances* nil)

(defn with-test-namespace [f]
  ;; Create a unique base namespace for each test run
  (let [base-ns (symbol (str "seon.test.ns-instance-base-" (System/currentTimeMillis)))]
    (create-ns base-ns)
    (binding [*ns* (find-ns base-ns)]
      (refer-clojure)
      (eval '(defn greet [n] (str "Hello, " n)))
      (eval '(defn double-it [x] (* x 2)))
      (eval '(def counter 0))
      (eval '(defmacro with-result [& body] `(do ~@body :done))))
    (binding [*test-base-ns* base-ns
              *test-instances* (atom [])]
      (try
        (f)
        (finally
          ;; Cleanup all instances
          (doseq [inst @*test-instances*]
            (try (ns-inst/destroy-instance-ns inst) (catch Exception _)))
          ;; Remove base namespace
          (try (remove-ns base-ns) (catch Exception _)))))))

(use-fixtures :each with-test-namespace)

(defn create-test-instance!
  "Create an instance and track it for cleanup."
  ([]
   (create-test-instance! {}))
  ([opts]
   (let [inst (ns-inst/create-instance-ns *test-base-ns* opts)]
     (swap! *test-instances* conj inst)
     inst)))

;;; ---------------------------------------------------------------------------
;;; Isolation Tests
;;; ---------------------------------------------------------------------------

(deftest instances-see-base-functions-test
  (testing "both instances can call base functions"
    (let [inst-1 (create-test-instance!)
          inst-2 (create-test-instance!)]
      (is (= "Hello, Alice" (ns-inst/instance-eval inst-1 "(greet \"Alice\")")))
      (is (= "Hello, Bob" (ns-inst/instance-eval inst-2 "(greet \"Bob\")")))
      (is (= 10 (ns-inst/instance-eval inst-1 "(double-it 5)")))
      (is (= 20 (ns-inst/instance-eval inst-2 "(double-it 10)"))))))

(deftest override-isolation-test
  (testing "override in one instance doesn't affect another"
    (let [inst-1 (create-test-instance!)
          inst-2 (create-test-instance!)]
      ;; Override greet in inst-1
      (ns-inst/instance-eval inst-1 "(defn greet [n] (str \"Hi, \" n))")

      ;; inst-1 has override, inst-2 has original
      (is (= "Hi, Alice" (ns-inst/instance-eval inst-1 "(greet \"Alice\")")))
      (is (= "Hello, Bob" (ns-inst/instance-eval inst-2 "(greet \"Bob\")"))))))

(deftest base-unchanged-test
  (testing "modifications in instance don't affect base"
    (let [inst (create-test-instance!)]
      ;; Override in instance
      (ns-inst/instance-eval inst "(defn greet [n] (str \"Yo, \" n))")

      ;; Verify instance has override
      (is (= "Yo, Test" (ns-inst/instance-eval inst "(greet \"Test\")")))

      ;; Verify base is unchanged
      (is (= "Hello, Base"
             (binding [*ns* (find-ns *test-base-ns*)]
               (eval '(greet "Base"))))))))

(deftest new-var-isolation-test
  (testing "new var in one instance not visible in another"
    (let [inst-1 (create-test-instance!)
          inst-2 (create-test-instance!)]
      ;; Define new var in inst-1
      (ns-inst/instance-eval inst-1 "(def my-secret 42)")

      ;; inst-1 has it
      (is (= 42 (ns-inst/instance-eval inst-1 "my-secret")))

      ;; inst-2 doesn't
      (is (thrown? Exception (ns-inst/instance-eval inst-2 "my-secret"))))))

(deftest destroy-removes-namespace-test
  (testing "destroy-instance-ns removes the namespace"
    (let [inst (create-test-instance! {:instance-id "test-destroy"})
          ns-sym (ns-name (:ns inst))]
      ;; Namespace exists
      (is (some? (find-ns ns-sym)))

      ;; Destroy it
      (ns-inst/destroy-instance-ns inst)

      ;; Namespace gone
      (is (nil? (find-ns ns-sym)))

      ;; Remove from tracked instances since we destroyed manually
      (swap! *test-instances* (fn [insts] (remove #(= (:id %) "test-destroy") insts))))))

;;; ---------------------------------------------------------------------------
;;; Reference vs Copy Tests
;;; ---------------------------------------------------------------------------

(deftest base-change-visible-test
  (testing "changes to base are visible in instance (reference semantics)"
    (let [inst (create-test-instance!)]
      ;; Initial value
      (is (= "Hello, Test" (ns-inst/instance-eval inst "(greet \"Test\")")))

      ;; Modify base
      (binding [*ns* (find-ns *test-base-ns*)]
        (eval '(defn greet [n] (str "Modified, " n))))

      ;; Instance sees change (unless it has override)
      (is (= "Modified, Test" (ns-inst/instance-eval inst "(greet \"Test\")"))))))

(deftest override-blocks-base-changes-test
  (testing "after override, instance doesn't see base changes"
    (let [inst (create-test-instance!)]
      ;; Override in instance first
      (ns-inst/instance-eval inst "(defn greet [n] (str \"Custom, \" n))")

      ;; Modify base
      (binding [*ns* (find-ns *test-base-ns*)]
        (eval '(defn greet [n] (str "NewBase, " n))))

      ;; Instance keeps its override
      (is (= "Custom, Test" (ns-inst/instance-eval inst "(greet \"Test\")"))))))

;;; ---------------------------------------------------------------------------
;;; Macro Tests
;;; ---------------------------------------------------------------------------

(deftest macro-inheritance-test
  (testing "macros from base work in instance"
    (let [inst (create-test-instance!)]
      ;; The with-result macro should expand and run correctly
      (is (= :done (ns-inst/instance-eval inst "(with-result 1 2 3)"))))))

(deftest macro-with-gensym-test
  (testing "macros with gensyms work correctly"
    ;; Create a more complex macro in base
    (binding [*ns* (find-ns *test-base-ns*)]
      (eval '(defmacro with-timing [& body]
               `(let [start# (System/currentTimeMillis)]
                  ~@body
                  (- (System/currentTimeMillis) start#)))))

    (let [inst (create-test-instance!)]
      ;; Should be a number >= 0
      (is (number? (ns-inst/instance-eval inst "(with-timing (+ 1 2))"))))))

;;; ---------------------------------------------------------------------------
;;; Keyword Resolution Tests
;;; ---------------------------------------------------------------------------

(deftest keyword-resolves-to-instance-ns-test
  (testing ":: keywords resolve to instance namespace, not base"
    (let [inst (create-test-instance!)
          kw-info (ns-inst/keyword-namespace-test inst)]
      ;; The keyword namespace should be the instance, not the base
      (is (= (str (ns-name (:ns inst)))
             (:keyword-test kw-info)))
      ;; Specifically, it should NOT be the base namespace
      (is (not= (str *test-base-ns*)
                (:keyword-test kw-info))))))

;;; ---------------------------------------------------------------------------
;;; Registry Tests
;;; ---------------------------------------------------------------------------

(deftest registry-tracks-instances-test
  (testing "registry correctly tracks created instances"
    (let [inst-1 (create-test-instance! {:instance-id "reg1"})
          inst-2 (create-test-instance! {:instance-id "reg2"})
          instances (ns-inst/list-instances)]
      (is (some #{"reg1"} instances))
      (is (some #{"reg2"} instances)))))

(deftest get-instance-test
  (testing "can retrieve instance by ID"
    (let [inst (create-test-instance! {:instance-id "lookup"})
          retrieved (ns-inst/get-instance "lookup")]
      (is (some? retrieved))
      (is (= "lookup" (:id retrieved)))
      (is (= (:ns inst) (:ns retrieved))))))

;;; ---------------------------------------------------------------------------
;;; Introspection Tests
;;; ---------------------------------------------------------------------------

(deftest instance-own-vars-test
  (testing "instance-own-vars returns only instance-defined vars"
    (let [inst (create-test-instance!)]
      ;; Initially no own vars
      (is (empty? (ns-inst/instance-own-vars inst)))

      ;; Define some vars
      (ns-inst/instance-eval inst "(def x 1)")
      (ns-inst/instance-eval inst "(defn my-fn [] :mine)")

      ;; Now should have own vars
      (let [own-vars (ns-inst/instance-own-vars inst)]
        (is (= 2 (count own-vars)))
        (is (contains? own-vars 'x))
        (is (contains? own-vars 'my-fn))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest instance-eval-forms-test
  (testing "instance-eval-forms evaluates multiple forms"
    (let [inst (create-test-instance!)
          results (ns-inst/instance-eval-forms inst
                                               '(def a 1)
                                               '(def b 2)
                                               '(+ a b))]
      (is (= 3 (count results)))
      (is (= 3 (last results))))))

(deftest instance-value-test
  (testing "instance-value retrieves var value"
    (let [inst (create-test-instance!)]
      (ns-inst/instance-eval inst "(def my-val 42)")
      (is (= 42 (ns-inst/instance-value inst 'my-val)))
      ;; Returns nil for non-existent var
      (is (nil? (ns-inst/instance-value inst 'not-defined))))))
