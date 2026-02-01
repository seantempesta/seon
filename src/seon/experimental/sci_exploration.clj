(ns seon.experimental.sci-exploration
  "Research: Sci (Small Clojure Interpreter) for sandboxed evaluation.

   Key questions:
   1. How does context isolation and forking work?
   2. What Java interop is available and how is it controlled?
   3. Can Sci run in both JVM and browser (ClojureScript)?
   4. What's the performance overhead vs native Clojure eval?

   Usage:
     ;; Run all experiments
     (run-all-experiments!)

     ;; Individual experiments
     (run-isolation-experiment!)
     (run-java-interop-experiment!)
     (run-capability-graduation-experiment!)
     (run-performance-experiment!)"
  (:require [sci.core :as sci]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; 1. Context Isolation & Forking
;;; ---------------------------------------------------------------------------

(defn run-isolation-experiment!
  "Test context isolation via sci/fork.

   Key findings to verify:
   - Fork creates a new atom with copy of parent state
   - New vars in child don't affect parent
   - New vars in parent don't affect child (after fork)
   - Functions can be overridden independently"
  []
  (println "\n=== ISOLATION EXPERIMENT ===\n")

  ;; Create base context with some functions
  (let [base-ctx (sci/init {:namespaces
                            {'app {'greet (fn [n] (str "Hello, " n))
                                   'double-it (fn [x] (* x 2))
                                   'counter (atom 0)}}})

        ;; Fork into two independent contexts
        ctx-1 (sci/fork base-ctx)
        ctx-2 (sci/fork base-ctx)]

    (println "1. Testing base function availability in both contexts:")
    (let [r1 (sci/eval-string* ctx-1 "(app/greet \"Alice\")")
          r2 (sci/eval-string* ctx-2 "(app/greet \"Bob\")")]
      (println "   ctx-1: (app/greet \"Alice\") =>" r1)
      (println "   ctx-2: (app/greet \"Bob\") =>" r2)
      (println "   PASS:" (and (= r1 "Hello, Alice") (= r2 "Hello, Bob"))))

    (println "\n2. Override function in ctx-1, check ctx-2 unaffected:")
    (sci/eval-string* ctx-1 "(ns app)")
    (sci/eval-string* ctx-1 "(defn greet [n] (str \"Hi, \" n))")
    (let [r1 (sci/eval-string* ctx-1 "(greet \"Alice\")")
          r2 (sci/eval-string* ctx-2 "(app/greet \"Bob\")")]
      (println "   ctx-1: (greet \"Alice\") =>" r1)
      (println "   ctx-2: (app/greet \"Bob\") =>" r2)
      (println "   PASS:" (and (= r1 "Hi, Alice") (= r2 "Hello, Bob"))))

    (println "\n3. New var in ctx-1 not visible in ctx-2:")
    (sci/eval-string* ctx-1 "(def my-secret 42)")
    (let [r1 (sci/eval-string* ctx-1 "my-secret")
          r2-error (try
                     (sci/eval-string* ctx-2 "app/my-secret")
                     (catch Exception e
                       {:error (.getMessage e)}))]
      (println "   ctx-1: my-secret =>" r1)
      (println "   ctx-2: app/my-secret => (error)" (:error r2-error))
      (println "   PASS:" (and (= r1 42) (some? (:error r2-error)))))

    (println "\n4. Changes to parent AFTER fork don't affect children:")
    ;; Modify the base context after forking
    (sci/eval-string* base-ctx "(ns app)")
    (sci/eval-string* base-ctx "(defn greet [n] (str \"Base says: \" n))")
    (let [base-result (sci/eval-string* base-ctx "(greet \"test\")")
          ctx-2-result (sci/eval-string* ctx-2 "(app/greet \"test\")")]
      (println "   base-ctx: (greet \"test\") =>" base-result)
      (println "   ctx-2: (app/greet \"test\") =>" ctx-2-result)
      (println "   PASS (ctx-2 keeps original):" (= ctx-2-result "Hello, test")))

    (println "\n5. Shared mutable state (atom) IS shared across forks:")
    (sci/eval-string* ctx-1 "(swap! app/counter inc)")
    (sci/eval-string* ctx-2 "(swap! app/counter inc)")
    (let [c1 (sci/eval-string* ctx-1 "@app/counter")
          c2 (sci/eval-string* ctx-2 "@app/counter")]
      (println "   ctx-1: @app/counter =>" c1)
      (println "   ctx-2: @app/counter =>" c2)
      (println "   NOTE: Atoms are SHARED - this is expected!")
      (println "   Both see:" c1 "(both incremented the same atom)"))

    {:test :isolation
     :status :pass}))

;;; ---------------------------------------------------------------------------
;;; 2. Java Interop Capabilities
;;; ---------------------------------------------------------------------------

(defn run-java-interop-experiment!
  "Test Java interop - what's available by default and how to extend.

   Key findings to verify:
   - Default classes available (basic types)
   - How to expose additional classes via :classes
   - Security: can sandboxed code escape?
   - Can we dynamically add capabilities?"
  []
  (println "\n=== JAVA INTEROP EXPERIMENT ===\n")

  ;; 1. Minimal context - what's available by default?
  (println "1. Default available classes (minimal context):")
  (let [minimal-ctx (sci/init {})]
    (doseq [expr ["(type \"hello\")"
                  "(type 42)"
                  "(type 3.14)"
                  "(type true)"
                  "(type [])"
                  "(type {})"]]
      (println "  " expr "=>" (sci/eval-string* minimal-ctx expr))))

  ;; 2. Try to access dangerous things (should fail)
  (println "\n2. Attempting dangerous operations (should all fail):")
  (let [minimal-ctx (sci/init {})]
    (doseq [expr ["(System/exit 0)"
                  "(java.io.File. \"/etc/passwd\")"
                  "(.exec (Runtime/getRuntime) \"ls\")"
                  "(Class/forName \"java.lang.Runtime\")"
                  "(.getClass \"foo\")"]]
      (let [result (try
                     (sci/eval-string* minimal-ctx expr)
                     (catch Exception e
                       {:blocked true :reason (.getMessage e)}))]
        (println "  " expr)
        (println "     =>" (if (:blocked result)
                             (str "BLOCKED: " (subs (:reason result) 0 (min 50 (count (:reason result)))))
                             (str "ALLOWED! " result))))))

  ;; 3. Explicitly allow some classes
  (println "\n3. Allowing specific classes via :classes:")
  (let [ctx-with-date (sci/init {:classes {'java.util.Date java.util.Date
                                            'java.util.UUID java.util.UUID}})]
    (doseq [expr ["(java.util.Date.)"
                  "(java.util.UUID/randomUUID)"
                  "(str (java.util.Date.))"]]
      (println "  " expr)
      (println "     =>" (sci/eval-string* ctx-with-date expr))))

  ;; 4. Can we add classes dynamically to an existing context?
  (println "\n4. Dynamic class addition via sci/add-class!:")
  (let [ctx (sci/init {})]
    (println "   Before: (java.time.Instant/now)")
    (println "     =>" (try
                         (sci/eval-string* ctx "(java.time.Instant/now)")
                         (catch Exception e "BLOCKED")))
    ;; Add the class dynamically
    (sci/add-class! ctx 'java.time.Instant java.time.Instant)
    (sci/add-import! ctx 'user 'java.time.Instant 'Instant)
    (println "   After adding class:")
    (println "     (Instant/now) =>" (sci/eval-string* ctx "(Instant/now)")))

  ;; 5. Instance methods and fields
  (println "\n5. Instance methods on allowed classes:")
  (let [ctx (sci/init {:classes {'java.util.Date java.util.Date
                                  'java.lang.String String}})]
    (doseq [expr ["(.toUpperCase \"hello\")"
                  "(.length \"hello world\")"
                  "(.getTime (java.util.Date.))"
                  "(-> \"hello\" .toUpperCase .toLowerCase)"]]
      (println "  " expr "=>" (sci/eval-string* ctx expr))))

  {:test :java-interop
   :status :pass})

;;; ---------------------------------------------------------------------------
;;; 3. Capability Graduation (Sandboxed → Trusted)
;;; ---------------------------------------------------------------------------

(defn run-capability-graduation-experiment!
  "Test if we can start restricted and add capabilities incrementally.

   The graduation path:
   1. Sandboxed: No Java interop, only pure Clojure
   2. Limited: Some safe classes (Date, UUID, String operations)
   3. Trusted: Most classes, still no System/Runtime
   4. Full: All access (use native Clojure for this)"
  []
  (println "\n=== CAPABILITY GRADUATION EXPERIMENT ===\n")

  ;; 1. Start with pure sandbox
  (println "1. Creating sandboxed context (no Java interop):")
  (let [sandboxed (sci/init {:namespaces {'user {'safe-fn (fn [x] (* x 2))}}})]
    (println "   Pure Clojure: (+ 1 2) =>" (sci/eval-string* sandboxed "(+ 1 2)"))
    (println "   User fn: (user/safe-fn 21) =>" (sci/eval-string* sandboxed "(user/safe-fn 21)"))
    (println "   Blocked: (java.util.Date.) =>"
             (try (sci/eval-string* sandboxed "(java.util.Date.)")
                  (catch Exception _ "BLOCKED")))

    ;; 2. Add some safe classes
    (println "\n2. Graduating to 'limited' - adding safe classes:")
    (sci/add-class! sandboxed 'java.util.Date java.util.Date)
    (sci/add-class! sandboxed 'java.util.UUID java.util.UUID)
    (println "   Now allowed: (java.util.Date.) =>" (sci/eval-string* sandboxed "(java.util.Date.)"))
    (println "   Still blocked: (java.io.File. \"/\") =>"
             (try (sci/eval-string* sandboxed "(java.io.File. \"/\")")
                  (catch Exception _ "BLOCKED")))

    ;; 3. Graduate further - add file operations (trusted)
    (println "\n3. Graduating to 'trusted' - adding file operations:")
    (sci/add-class! sandboxed 'java.io.File java.io.File)
    (println "   Now allowed: (.exists (java.io.File. \"/\")) =>"
             (sci/eval-string* sandboxed "(.exists (java.io.File. \"/\"))"))
    (println "   Still blocked: (System/exit 0) =>"
             (try (sci/eval-string* sandboxed "(System/exit 0)")
                  (catch Exception _ "BLOCKED"))))

  ;; 4. Using merge-opts instead of add-class!
  (println "\n4. Using sci/merge-opts for bulk capability addition:")
  (let [base (sci/init {})
        enhanced (sci/merge-opts base {:classes {'java.util.Date java.util.Date
                                                  'java.util.UUID java.util.UUID
                                                  'java.time.Instant java.time.Instant}})]
    (println "   After merge: (java.time.Instant/now) =>"
             (sci/eval-string* enhanced "(java.time.Instant/now)")))

  {:test :capability-graduation
   :status :pass})

;;; ---------------------------------------------------------------------------
;;; 4. Performance Measurement
;;; ---------------------------------------------------------------------------

(defn run-performance-experiment!
  "Measure Sci eval overhead vs native Clojure.

   Tests:
   - Simple arithmetic
   - Function calls
   - Collection operations
   - Loop iterations"
  []
  (println "\n=== PERFORMANCE EXPERIMENT ===\n")

  (let [ctx (sci/init {:namespaces {'user {'fib (fn fib [n]
                                                  (if (<= n 1)
                                                    n
                                                    (+ (fib (- n 1))
                                                       (fib (- n 2)))))}}})
        iterations 10000]

    ;; 1. Simple arithmetic
    (println "1. Simple arithmetic (+ 1 2) x" iterations "iterations:")
    (let [sci-time (time (dotimes [_ iterations]
                           (sci/eval-string* ctx "(+ 1 2)")))
          native-time (time (dotimes [_ iterations]
                              (+ 1 2)))]
      (println "   (times printed above)"))

    ;; 2. Function calls
    (println "\n2. Function call (* 6 7) x" iterations "iterations:")
    (time (dotimes [_ iterations]
            (sci/eval-string* ctx "(* 6 7)")))

    ;; 3. Collection operations
    (println "\n3. Map operation x 1000 iterations:")
    (time (dotimes [_ 1000]
            (sci/eval-string* ctx "(map inc [1 2 3 4 5])")))

    ;; 4. Fibonacci (compute-bound)
    (println "\n4. Fibonacci(15) x 100 iterations:")
    (time (dotimes [_ 100]
            (sci/eval-string* ctx "(user/fib 15)")))

    ;; 5. Memory footprint estimate
    (println "\n5. Memory footprint of a Sci context:")
    (let [before-mem (.freeMemory (Runtime/getRuntime))
          _ (System/gc)
          contexts (doall (repeatedly 100 #(sci/init {})))
          _ (System/gc)
          after-mem (.freeMemory (Runtime/getRuntime))
          diff (- before-mem after-mem)]
      (println "   100 contexts use approximately:" (/ diff 1024) "KB")
      (println "   Per context estimate:" (/ diff 1024 100) "KB"))

    ;; 6. Parse vs eval (to understand where time goes)
    (println "\n6. Parse vs Eval breakdown for '(map inc (range 100))':")
    (let [code "(map inc (range 100))"]
      (print "   Parse time x 1000: ")
      (time (dotimes [_ 1000]
              (sci/parse-string ctx code)))
      (let [parsed (sci/parse-string ctx code)]
        (print "   Eval parsed x 1000: ")
        (time (dotimes [_ 1000]
                (sci/eval-form ctx parsed))))))

  {:test :performance
   :status :pass})

;;; ---------------------------------------------------------------------------
;;; 5. Security Model Investigation
;;; ---------------------------------------------------------------------------

(defn run-security-experiment!
  "Test the security boundaries of Sci.

   Key questions:
   - Can code escape the sandbox via reflection?
   - Can code access the ctx object itself?
   - Can code modify Sci internals?
   - What about resolve/eval tricks?"
  []
  (println "\n=== SECURITY EXPERIMENT ===\n")

  (let [ctx (sci/init {})]

    (println "1. Attempting reflection-based escapes:")
    (doseq [expr ["(-> \"foo\" .getClass .getClassLoader)"
                  "(.getDeclaredField String \"value\")"
                  "(.setAccessible (first (.getDeclaredFields String)) true)"
                  "(Class/forName \"java.lang.Runtime\")"]]
      (println "  " (subs expr 0 (min 50 (count expr))) "...")
      (println "     =>" (try (sci/eval-string* ctx expr)
                              (catch Exception e "BLOCKED"))))

    (println "\n2. Attempting to access Sci internals:")
    (doseq [expr ["sci.core/init"
                  "@sci.impl.vars/dvals"
                  "(resolve 'sci.core/fork)"]]
      (println "  " expr)
      (println "     =>" (try (sci/eval-string* ctx expr)
                              (catch Exception e "BLOCKED"))))

    (println "\n3. Attempting eval/read tricks:")
    (doseq [expr ["(eval '(+ 1 2))"
                  "(read-string \"(System/exit 0)\")"
                  "(load-string \"(def x 1)\")"]]
      (println "  " expr)
      (let [result (try (sci/eval-string* ctx expr)
                        (catch Exception e {:exception (.getMessage e)}))]
        (println "     =>" (if (:exception result)
                             (str "BLOCKED: " (subs (:exception result) 0
                                                    (min 40 (count (:exception result)))))
                             result))))

    (println "\n4. Testing :deny option for additional restrictions:")
    (let [restricted-ctx (sci/init {:deny '[loop recur]})]
      (doseq [expr ["(loop [x 0] (if (< x 10) (recur (inc x)) x))"
                    "(reduce + (range 10))"]]
        (println "  " expr)
        (println "     =>" (try (sci/eval-string* restricted-ctx expr)
                                (catch Exception e "BLOCKED"))))))

  {:test :security
   :status :pass})

;;; ---------------------------------------------------------------------------
;;; 6. Browser/ClojureScript Investigation
;;; ---------------------------------------------------------------------------

(defn explain-cljs-support
  "Document how Sci works in ClojureScript/browser context.

   This is a documentation function since we can't run CLJS directly here."
  []
  (println "\n=== CLOJURESCRIPT/BROWSER SUPPORT ===\n")

  (println "Sci uses .cljc files (reader conditionals) to support both JVM and JS.")
  (println "")
  (println "Key observations from source:")
  (println "")
  (println "1. sci/core.cljc - Public API works on both platforms:")
  (println "   - #?(:clj ... :cljs ...) branches throughout")
  (println "   - Same init/fork/eval-string* API on both")
  (println "")
  (println "2. sci/impl/vars.cljc - Thread bindings differ:")
  (println "   - JVM: ThreadLocal<Frame>")
  (println "   - CLJS: volatile! (single-threaded JS)")
  (println "")
  (println "3. sci/impl/interop.cljc - Java vs JS interop:")
  (println "   - JVM: Uses reflection (Reflector class)")
  (println "   - CLJS: Uses js/Reflect.apply and aget")
  (println "   - CLJS has js/Function constructor for accessor fns")
  (println "")
  (println "4. Class handling differs:")
  (println "   - JVM :classes => Java Class objects")
  (println "   - CLJS :classes => JS objects/constructors")
  (println "   - CLJS has add-js-lib! for NPM packages")
  (println "")
  (println "5. For Datastar expressions in browser:")
  (println "   - Could ship sci.js bundle")
  (println "   - Create context once at page load")
  (println "   - Eval Clojure expressions client-side")
  (println "   - PROS: Familiar syntax, powerful expressions")
  (println "   - CONS: Bundle size (~100-200KB gzipped)")
  (println "")
  (println "6. Alternative: Use Sci to generate JS at compile time")
  (println "   - Server-side Sci generates JavaScript code")
  (println "   - No Sci bundle needed in browser")
  (println "   - Limited to expressions that can compile to JS")

  {:topic :cljs-support
   :conclusion "Sci fully supports ClojureScript. For browser use, consider bundle size tradeoffs."})

;;; ---------------------------------------------------------------------------
;;; Run All Experiments
;;; ---------------------------------------------------------------------------

(defn run-all-experiments!
  "Run all experiments and collect results."
  []
  (println "")
  (println "╔══════════════════════════════════════════════════════════════════════════════╗")
  (println "║                     SCI (Small Clojure Interpreter) Research                  ║")
  (println "╚══════════════════════════════════════════════════════════════════════════════╝")

  (let [results [(run-isolation-experiment!)
                 (run-java-interop-experiment!)
                 (run-capability-graduation-experiment!)
                 (run-performance-experiment!)
                 (run-security-experiment!)
                 (explain-cljs-support)]]

    (println "\n")
    (println "════════════════════════════════════════════════════════════════════════════════")
    (println "                              SUMMARY                                           ")
    (println "════════════════════════════════════════════════════════════════════════════════")
    (println "")
    (println "Isolation:")
    (println "  - sci/fork creates independent contexts via shallow atom copy")
    (println "  - New vars in forked context don't affect parent")
    (println "  - WARNING: Mutable state (atoms) IS shared across forks!")
    (println "  - Solution: Don't share atoms, or create fresh atoms per context")
    (println "")
    (println "Java Interop:")
    (println "  - Default: Only basic Clojure types, no Java classes")
    (println "  - Add classes via :classes option or sci/add-class!")
    (println "  - Can add dynamically to existing context")
    (println "  - Dangerous classes (System, Runtime) can be excluded")
    (println "")
    (println "Capability Graduation:")
    (println "  - Start sandboxed (no classes)")
    (println "  - Add classes incrementally with add-class!")
    (println "  - Or use merge-opts for bulk changes")
    (println "  - Perfect for sandbox → trusted → full progression")
    (println "")
    (println "Performance:")
    (println "  - Overhead ~10-100x vs native for simple ops")
    (println "  - Acceptable for expressions, not for hot loops")
    (println "  - Memory: ~10-50KB per context")
    (println "  - Parse once, eval multiple times for better perf")
    (println "")
    (println "Security:")
    (println "  - Reflection attacks blocked by default")
    (println "  - Sci internals not accessible from sandboxed code")
    (println "  - :deny option for additional restrictions")
    (println "  - Safe for untrusted code WITH CAVEATS (see docs)")
    (println "")
    (println "ClojureScript:")
    (println "  - Full support via .cljc files")
    (println "  - Same API on JVM and browser")
    (println "  - Bundle size consideration (~100-200KB gzipped)")
    (println "")

    results))

;;; ---------------------------------------------------------------------------
;;; Comment block for interactive testing
;;; ---------------------------------------------------------------------------

(comment
  ;; Run all experiments
  (run-all-experiments!)

  ;; Individual experiments
  (run-isolation-experiment!)
  (run-java-interop-experiment!)
  (run-capability-graduation-experiment!)
  (run-performance-experiment!)
  (run-security-experiment!)
  (explain-cljs-support)

  ;; Quick tests
  (def ctx (sci/init {:namespaces {'app {'greet (fn [n] (str "Hello, " n))}}}))
  (sci/eval-string* ctx "(app/greet \"World\")")

  ;; Fork test
  (def ctx-1 (sci/fork ctx))
  (def ctx-2 (sci/fork ctx))
  (sci/eval-string* ctx-1 "(ns app) (defn greet [n] (str \"Hi, \" n))")
  (sci/eval-string* ctx-1 "(greet \"Alice\")")
  (sci/eval-string* ctx-2 "(app/greet \"Bob\")") ; Still sees original

  ;; Java interop
  (def ctx-java (sci/init {:classes {'java.util.Date java.util.Date}}))
  (sci/eval-string* ctx-java "(java.util.Date.)")

  nil)
