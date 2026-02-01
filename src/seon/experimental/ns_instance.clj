(ns seon.experimental.ns-instance
  "Research: Dynamic namespace isolation for agents.

   This namespace explores whether Clojure's built-in namespace functions
   can provide sufficient CODE isolation for namespace instances.

   Key questions:
   1. Does `intern` copy or reference the original var?
   2. When base function changes, do instances see the change?
   3. Do macros expand correctly in instance namespace?
   4. What happens with `::keyword` shorthand in instances?

   Usage:
     ;; Create instance namespace from base
     (def inst-1 (create-instance-ns 'seon.web.reactive.demo \"a1b2\"))

     ;; Eval code in instance
     (instance-eval inst-1 \"(defn greet [n] (str \\\"Hi \\\" n))\")

     ;; Cleanup
     (destroy-instance-ns inst-1)"
  (:require [taoensso.timbre :as log])
  (:import [java.security SecureRandom]))

;;; ---------------------------------------------------------------------------
;;; ID Generation (matching instance.clj pattern)
;;; ---------------------------------------------------------------------------

(def ^:private secure-random (SecureRandom.))

(defn generate-instance-id
  "Generate a 4-character hex instance ID."
  []
  (let [bytes (byte-array 2)]
    (.nextBytes secure-random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

;;; ---------------------------------------------------------------------------
;;; Registry
;;; ---------------------------------------------------------------------------

;; Map of instance-id -> {:ns namespace-object :base-ns symbol :created-at inst}
(defonce ^:private registry (atom {}))

;;; ---------------------------------------------------------------------------
;;; Core Functions
;;; ---------------------------------------------------------------------------

(defn create-instance-ns
  "Create an instance namespace that inherits from base.

   The instance namespace:
   - Has all public vars from base-ns available
   - Can define new vars that don't affect base
   - Can override base vars independently

   Options:
     :instance-id - Optional. Use specific ID instead of generating one.

   Returns map with:
     :id          - 4-char hex instance ID
     :ns          - The created namespace object
     :base-ns     - The base namespace symbol
     :created-at  - When instance was created

   Example:
     (create-instance-ns 'seon.web.reactive.demo)
     ;; => {:id \"a1b2\" :ns #namespace[seon.web.reactive.demo.a1b2] ...}"
  ([base-ns-sym]
   (create-instance-ns base-ns-sym {}))
  ([base-ns-sym {:keys [instance-id] :as _opts}]
   (let [instance-id (or instance-id (generate-instance-id))
         instance-sym (symbol (str base-ns-sym "." instance-id))
         ;; Ensure base namespace exists and is loaded
         ;; Only require if namespace doesn't exist (allows dynamic namespaces)
         _ (when-not (find-ns base-ns-sym)
             (require base-ns-sym))
         base-ns (find-ns base-ns-sym)
         _ (when-not base-ns
             (throw (ex-info "Base namespace not found" {:base-ns base-ns-sym})))
         ;; Create new namespace
         instance-ns (create-ns instance-sym)
         created-at (java.util.Date.)]

     ;; First, refer clojure.core so basic functions work
     (binding [*ns* instance-ns]
       (refer-clojure))

     ;; Use `refer` to bring in all public vars from base
     ;; This preserves macro metadata and allows overriding
     ;; Changes to base ARE visible until instance overrides
     (binding [*ns* instance-ns]
       (refer base-ns-sym))

     ;; Set up alias for :: keyword resolution
     ;; This allows ::base/foo to resolve to :base-ns/foo
     (binding [*ns* instance-ns]
       (let [base-name (-> base-ns-sym name symbol)]
         (try
           (alias base-name base-ns-sym)
           (catch Exception e
             (log/debug "Could not create alias" {:base base-ns-sym :error (.getMessage e)})))))

     ;; Register in registry
     (let [info {:id instance-id
                 :ns instance-ns
                 :base-ns base-ns-sym
                 :created-at created-at}]
       (swap! registry assoc instance-id info)
       (log/info "Created instance namespace" {:id instance-id :base base-ns-sym})
       info))))

(defn instance-eval
  "Eval code string in an instance namespace.

   The code is read and evaluated with *ns* bound to the instance namespace.
   This means:
   - `def` and `defn` create vars in the instance namespace
   - Unqualified symbols resolve in instance first, then base
   - `::foo` expands to `:instance-ns/foo` NOT `:base-ns/foo`

   Returns the result of evaluation.

   Example:
     (instance-eval inst \"(defn greet [n] (str \\\"Hi \\\" n))\")
     (instance-eval inst \"(greet \\\"world\\\")\")
     ;; => \"Hi world\""
  [instance-info code-str]
  (let [ns-obj (:ns instance-info)]
    (binding [*ns* ns-obj]
      (eval (read-string code-str)))))

(defn instance-eval-forms
  "Eval multiple forms in an instance namespace.

   Like instance-eval but takes Clojure forms directly instead of a string.
   Returns a vector of results for each form.

   Example:
     (instance-eval-forms inst
       '(def x 1)
       '(def y 2)
       '(+ x y))
     ;; => [#'inst.ns/x #'inst.ns/y 3]"
  [instance-info & forms]
  (let [ns-obj (:ns instance-info)]
    (binding [*ns* ns-obj]
      (mapv eval forms))))

(defn destroy-instance-ns
  "Destroy an instance namespace and remove from registry.

   This:
   - Removes the namespace via remove-ns
   - Removes from registry

   Returns true if instance was found and destroyed.

   Example:
     (destroy-instance-ns inst)"
  [instance-info]
  (let [id (:id instance-info)
        ns-obj (:ns instance-info)]
    (when ns-obj
      ;; Remove the namespace
      (remove-ns (ns-name ns-obj))
      ;; Remove from registry
      (swap! registry dissoc id)
      (log/info "Destroyed instance namespace" {:id id})
      true)))

(defn get-instance
  "Get instance info by ID.

   Returns nil if not found."
  [instance-id]
  (get @registry instance-id))

(defn list-instances
  "List all active instance IDs."
  []
  (vec (keys @registry)))

(defn instance-var
  "Get a var from an instance namespace.

   Returns the var if found, nil otherwise."
  [instance-info var-sym]
  (let [ns-obj (:ns instance-info)]
    (ns-resolve ns-obj var-sym)))

(defn instance-value
  "Get the value of a var in an instance namespace.

   Returns the dereferenced value if found, nil otherwise."
  [instance-info var-sym]
  (when-let [v (instance-var instance-info var-sym)]
    @v))

;;; ---------------------------------------------------------------------------
;;; Introspection
;;; ---------------------------------------------------------------------------

(defn instance-publics
  "Get map of public vars in an instance namespace.

   Note: This includes inherited vars from base namespace."
  [instance-info]
  (ns-publics (:ns instance-info)))

(defn instance-interns
  "Get map of vars interned directly in instance namespace.

   This is useful to see what was ADDED or OVERRIDDEN in the instance
   versus what was inherited from base."
  [instance-info]
  (ns-interns (:ns instance-info)))

(defn instance-refers
  "Get map of referred vars (inherited from base) in instance namespace."
  [instance-info]
  (ns-refers (:ns instance-info)))

(defn instance-own-vars
  "Get only the vars that were defined IN this instance, not inherited.

   This filters ns-interns to exclude vars that are just references
   to base namespace vars."
  [instance-info]
  (let [interns (ns-interns (:ns instance-info))
        base-ns (:base-ns instance-info)
        base-publics (ns-publics (find-ns base-ns))]
    (into {}
          (remove (fn [[sym var]]
                    (contains? base-publics sym)))
          interns)))

;;; ---------------------------------------------------------------------------
;;; The :: Keyword Problem
;;; ---------------------------------------------------------------------------

(defn keyword-namespace-test
  "Test how :: keywords resolve in an instance namespace.

   Returns info about keyword resolution for analysis."
  [instance-info]
  (let [ns-obj (:ns instance-info)
        ns-name-sym (ns-name ns-obj)]
    {:ns-name ns-name-sym
     :base-ns (:base-ns instance-info)
     ;; Evaluate :: keyword resolution in instance
     ;; Must use read-string so :: is read with *ns* bound to instance
     :keyword-test (binding [*ns* ns-obj]
                     (namespace (read-string "::test")))
     ;; What the keyword actually becomes
     :keyword-value (binding [*ns* ns-obj]
                      (read-string "::test"))
     ;; Expected value
     :expected-ns (str ns-name-sym)}))

;;; ---------------------------------------------------------------------------
;;; Isolation Tests (runnable experiments)
;;; ---------------------------------------------------------------------------

(defn run-isolation-experiment!
  "Run a complete isolation experiment and return results.

   This creates two instances from a demo base, modifies them independently,
   and verifies isolation properties."
  []
  (let [;; Create a simple base namespace for testing
        base-ns 'seon.experimental.ns-instance-base
        _ (create-ns base-ns)
        _ (binding [*ns* (find-ns base-ns)]
            (refer-clojure)
            (eval '(defn greet [n] (str "Hello, " n)))
            (eval '(defn double-it [x] (* x 2)))
            (eval '(def counter 0)))

        ;; Create two instances
        inst-1 (create-instance-ns base-ns {:instance-id "0001"})
        inst-2 (create-instance-ns base-ns {:instance-id "0002"})

        results (atom {:tests [] :passed 0 :failed 0})
        add-result! (fn [name passed? details]
                      (swap! results update :tests conj
                             {:name name :passed passed? :details details})
                      (swap! results update (if passed? :passed :failed) inc))]

    (try
      ;; Test 1: Both instances see base function
      (let [r1 (instance-eval inst-1 "(greet \"Alice\")")
            r2 (instance-eval inst-2 "(greet \"Bob\")")]
        (add-result! "Instances see base functions"
                     (and (= r1 "Hello, Alice") (= r2 "Hello, Bob"))
                     {:inst-1 r1 :inst-2 r2}))

      ;; Test 2: Override in inst-1, verify inst-2 unaffected
      (instance-eval inst-1 "(defn greet [n] (str \"Hi, \" n))")
      (let [r1 (instance-eval inst-1 "(greet \"Alice\")")
            r2 (instance-eval inst-2 "(greet \"Bob\")")]
        (add-result! "Override in inst-1 doesn't affect inst-2"
                     (and (= r1 "Hi, Alice") (= r2 "Hello, Bob"))
                     {:inst-1 r1 :inst-2 r2}))

      ;; Test 3: Base namespace unchanged
      (let [base-greet (binding [*ns* (find-ns base-ns)]
                         (eval '(greet "Test")))]
        (add-result! "Base namespace unchanged"
                     (= base-greet "Hello, Test")
                     {:base-result base-greet}))

      ;; Test 4: New var in inst-1 not visible in inst-2
      (instance-eval inst-1 "(def my-var 42)")
      (let [inst-1-has (some? (instance-var inst-1 'my-var))
            inst-2-has (some? (instance-var inst-2 'my-var))]
        (add-result! "New var in inst-1 not visible in inst-2"
                     (and inst-1-has (not inst-2-has))
                     {:inst-1-has inst-1-has :inst-2-has inst-2-has}))

      ;; Test 5: :: keyword resolution
      (let [kw-info (keyword-namespace-test inst-1)]
        (add-result! ":: keyword resolves to instance namespace"
                     (= (:keyword-test kw-info)
                        (str (ns-name (:ns inst-1))))
                     {:kw-info kw-info}))

      ;; Test 6: Destroy cleans up
      (destroy-instance-ns inst-1)
      (let [still-exists (some? (find-ns 'seon.experimental.ns-instance-base.0001))]
        (add-result! "Destroy removes namespace"
                     (not still-exists)
                     {:still-exists still-exists}))

      ;; Cleanup
      (destroy-instance-ns inst-2)
      (remove-ns base-ns)

      @results

      (catch Exception e
        ;; Cleanup on error
        (try (destroy-instance-ns inst-1) (catch Exception _))
        (try (destroy-instance-ns inst-2) (catch Exception _))
        (try (remove-ns base-ns) (catch Exception _))
        (throw e)))))

;;; ---------------------------------------------------------------------------
;;; Advanced: Var Reference vs Copy Investigation
;;; ---------------------------------------------------------------------------

(defn investigate-var-reference!
  "Investigate whether intern creates a reference or copy.

   Creates instance, modifies base, checks if instance sees change."
  []
  (let [base-ns 'seon.experimental.var-ref-test
        _ (create-ns base-ns)
        _ (binding [*ns* (find-ns base-ns)]
            (refer-clojure)
            (eval '(defn changeable [] "original")))

        inst (create-instance-ns base-ns)

        ;; Check initial value in instance
        before (instance-eval inst "(changeable)")

        ;; Modify base namespace
        _ (binding [*ns* (find-ns base-ns)]
            (eval '(defn changeable [] "modified")))

        ;; Check value in instance after base change
        after (instance-eval inst "(changeable)")

        ;; Cleanup
        _ (destroy-instance-ns inst)
        _ (remove-ns base-ns)]

    {:before before
     :after after
     :sees-change? (= after "modified")
     :conclusion (if (= after "modified")
                   "REFERENCE: Instance sees changes to base"
                   "COPY: Instance keeps original value")}))

;;; ---------------------------------------------------------------------------
;;; Comment block for interactive testing
;;; ---------------------------------------------------------------------------

(comment
  ;; Run the isolation experiment
  (run-isolation-experiment!)

  ;; Investigate var reference behavior
  (investigate-var-reference!)

  ;; Manual testing
  (def inst-1 (create-instance-ns 'seon.web.reactive.demo))
  (:id inst-1)

  ;; Check what's in the instance
  (keys (instance-publics inst-1))
  (keys (instance-interns inst-1))

  ;; Eval in instance
  (instance-eval inst-1 "(initial-state)")

  ;; Override a function
  (instance-eval inst-1 "(defn increment! [{:seon.reactive/keys [ctx]}]
                           (when ctx (swap! ctx update :count #(+ % 10))))")

  ;; Check own vars (not inherited)
  (instance-own-vars inst-1)

  ;; Test :: keyword
  (keyword-namespace-test inst-1)

  ;; List instances
  (list-instances)

  ;; Cleanup
  (destroy-instance-ns inst-1)

  nil)
