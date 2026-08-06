#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns class-eviction-repro
  (:import
   (clojure.asm ClassWriter Opcodes)
   (clojure.lang DynamicClassLoader RT)
   (com.sun.management HotSpotDiagnosticMXBean)
   (java.lang.management ManagementFactory)
   (java.lang.ref Reference SoftReference)
   (java.lang.reflect InvocationTargetException Method)
   (java.util UUID)
   (java.util.concurrent ConcurrentHashMap)))

;; Run with the child JVM's flags supplied by the :dev alias:
;; clojure -M:dev docs/prds/sci-execution-runtime/research/scripts/class_eviction_repro.clj

(def ^:private class-cache-field
  (doto (.getDeclaredField DynamicClassLoader "classCache")
    (.setAccessible true)))

(defn- class-cache
  ^ConcurrentHashMap []
  (.get class-cache-field nil))

(defn- vm-option
  [option-name]
  (let [server (ManagementFactory/getPlatformMBeanServer)
        bean (ManagementFactory/newPlatformMXBeanProxy
              server
              "com.sun.management:type=HotSpotDiagnostic"
              HotSpotDiagnosticMXBean)]
    (.getValue (.getVMOption bean option-name))))

(defn- require-evidence!
  [claim evidence]
  (when-not claim
    (throw (ex-info "Class-eviction evidence assertion failed." evidence))))

(defn- emit-constructor!
  [^ClassWriter writer]
  (let [method (.visitMethod writer Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)]
    (.visitCode method)
    (.visitVarInsn method Opcodes/ALOAD 0)
    (.visitMethodInsn method
                      Opcodes/INVOKESPECIAL
                      "java/lang/Object"
                      "<init>"
                      "()V"
                      false)
    (.visitInsn method Opcodes/RETURN)
    (.visitMaxs method 1 1)
    (.visitEnd method)))

(defn- target-bytes
  [internal-name]
  (let [writer (ClassWriter. 0)]
    (.visit writer Opcodes/V11 Opcodes/ACC_PUBLIC internal-name nil "java/lang/Object" nil)
    (emit-constructor! writer)
    (let [method (.visitMethod writer
                               (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                               "value"
                               "()I"
                               nil
                               nil)]
      (.visitCode method)
      (.visitIntInsn method Opcodes/BIPUSH 42)
      (.visitInsn method Opcodes/IRETURN)
      (.visitMaxs method 1 0)
      (.visitEnd method))
    (.visitEnd writer)
    (.toByteArray writer)))

(defn- caller-bytes
  [internal-name target-internal-name]
  (let [writer (ClassWriter. 0)]
    (.visit writer Opcodes/V11 Opcodes/ACC_PUBLIC internal-name nil "java/lang/Object" nil)
    (emit-constructor! writer)
    (let [method (.visitMethod writer
                               (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                               "call"
                               "()I"
                               nil
                               nil)]
      (.visitCode method)
      (.visitMethodInsn method
                        Opcodes/INVOKESTATIC
                        target-internal-name
                        "value"
                        "()I"
                        false)
      (.visitInsn method Opcodes/IRETURN)
      (.visitMaxs method 1 0)
      (.visitEnd method))
    (.visitEnd writer)
    (.toByteArray writer)))

(defn- define-class!
  [^DynamicClassLoader loader class-name bytes]
  (.defineClass loader class-name bytes nil))

(defn- identity-id
  [value]
  (when value
    (System/identityHashCode value)))

(defn- loader-chain
  [^ClassLoader initial-loader]
  (loop [loader initial-loader
         chain []]
    (if loader
      (recur (.getParent loader)
             (conj chain
                   {:class-eviction-repro/loader-id (identity-id loader)
                    :class-eviction-repro/loader-class (.getName (class loader))}))
      chain)))

(defn- throwable-chain
  [throwable]
  (loop [cause throwable
         chain []]
    (if cause
      (recur (.getCause ^Throwable cause)
             (conj chain
                   {:class-eviction-repro/throwable-class (.getName (class cause))
                    :class-eviction-repro/throwable-message (.getMessage ^Throwable cause)}))
      chain)))

(defn- invoke
  [^Method method]
  (try
    {:class-eviction-repro/value (.invoke method nil (object-array 0))}
    (catch InvocationTargetException exception
      {:class-eviction-repro/throwables (throwable-chain exception)})))

(defn- throwable-classes
  [invocation]
  (mapv :class-eviction-repro/throwable-class
        (:class-eviction-repro/throwables invocation)))

(defn- make-fixture
  []
  (let [suffix (.replace (str (UUID/randomUUID)) "-" "")
        package-name "seon.probe.eviction"
        target-name (str package-name ".Target" suffix)
        resolved-caller-name (str package-name ".ResolvedCaller" suffix)
        unresolved-caller-name (str package-name ".UnresolvedCaller" suffix)
        target-internal-name (.replace target-name "." "/")
        defining-loader (DynamicClassLoader. (RT/baseLoader))
        target-class (define-class! defining-loader
                                    target-name
                                    (target-bytes target-internal-name))
        resolved-loader (DynamicClassLoader. (RT/baseLoader))
        resolved-caller (define-class! resolved-loader
                                       resolved-caller-name
                                       (caller-bytes (.replace resolved-caller-name "." "/")
                                                     target-internal-name))
        unresolved-loader (DynamicClassLoader. (RT/baseLoader))
        unresolved-caller (define-class! unresolved-loader
                                         unresolved-caller-name
                                         (caller-bytes (.replace unresolved-caller-name "." "/")
                                                       target-internal-name))]
    {:class-eviction-repro/defining-loader defining-loader
     :class-eviction-repro/resolved-caller resolved-caller
     :class-eviction-repro/resolved-loader resolved-loader
     :class-eviction-repro/target-class target-class
     :class-eviction-repro/target-name target-name
     :class-eviction-repro/unresolved-caller unresolved-caller
     :class-eviction-repro/unresolved-loader unresolved-loader}))

(defn- run-probe!
  []
  (let [cache-before (class-cache)
        {::keys [defining-loader
                 resolved-caller
                 resolved-loader
                 target-class
                 target-name
                 unresolved-caller
                 unresolved-loader]}
        (make-fixture)
        cache-reference ^Reference (.get cache-before target-name)
        resolved-method (.getMethod ^Class resolved-caller "call" (make-array Class 0))
        unresolved-method (.getMethod ^Class unresolved-caller "call" (make-array Class 0))]
    (require-evidence!
     (instance? SoftReference cache-reference)
     {:class-eviction-repro/expected "SoftReference class-cache entry"
      :class-eviction-repro/target-name target-name})
    (let [first-resolved-invocation (invoke resolved-method)]
      (require-evidence!
       (= 42 (:class-eviction-repro/value first-resolved-invocation))
       {:class-eviction-repro/expected "resolved caller returns 42 before eviction"
        :class-eviction-repro/actual first-resolved-invocation})
      (prn
       {:class-eviction-repro/event :class-eviction-repro/before-eviction
        :class-eviction-repro/java-version (System/getProperty "java.version")
        :class-eviction-repro/clojure-version (clojure-version)
        :class-eviction-repro/vm-options
        {:class-eviction-repro/use-g1-gc (vm-option "UseG1GC")
         :class-eviction-repro/max-ram-percentage (vm-option "MaxRAMPercentage")
         :class-eviction-repro/g1-periodic-gc-interval (vm-option "G1PeriodicGCInterval")
         :class-eviction-repro/soft-ref-lru-policy-ms-per-mb
         (vm-option "SoftRefLRUPolicyMSPerMB")}
        :class-eviction-repro/max-heap-mib
        (quot (.maxMemory (Runtime/getRuntime)) (* 1024 1024))
        :class-eviction-repro/cache-id (identity-id cache-before)
        :class-eviction-repro/cache-reference-class (.getName (class cache-reference))
        :class-eviction-repro/cache-referent-class (.getName ^Class (.get cache-reference))
        :class-eviction-repro/target-name target-name
        :class-eviction-repro/target-class-id (identity-id target-class)
        :class-eviction-repro/defining-loader-id (identity-id defining-loader)
        :class-eviction-repro/defining-loader-chain (loader-chain defining-loader)
        :class-eviction-repro/resolved-loader-id (identity-id resolved-loader)
        :class-eviction-repro/unresolved-loader-id (identity-id unresolved-loader)
        :class-eviction-repro/resolved-before-clear first-resolved-invocation})
      (.clear cache-reference)
      (let [enqueued? (.enqueue cache-reference)
            cache-after-clear (class-cache)]
        (require-evidence!
         (and (identical? cache-before cache-after-clear)
              (.containsKey cache-after-clear target-name)
              (nil? (.get cache-reference)))
         {:class-eviction-repro/expected
          "same cache map retains a cleared target reference before lookup"
          :class-eviction-repro/cache-identical?
          (identical? cache-before cache-after-clear)
          :class-eviction-repro/cache-key-present?
          (.containsKey cache-after-clear target-name)
          :class-eviction-repro/cache-referent (.get cache-reference)})
        (prn
         {:class-eviction-repro/event :class-eviction-repro/after-forced-clear
          :class-eviction-repro/cache-id (identity-id cache-after-clear)
          :class-eviction-repro/cache-identical? (identical? cache-before cache-after-clear)
          :class-eviction-repro/cache-key-present? (.containsKey cache-after-clear target-name)
          :class-eviction-repro/cache-referent-alive? (some? (.get cache-reference))
          :class-eviction-repro/reference-enqueued? enqueued?})
        (let [unresolved-invocation (invoke unresolved-method)
              resolved-after-clear (invoke resolved-method)
              failure-classes (throwable-classes unresolved-invocation)
              cache-after-lookup (class-cache)]
          (require-evidence!
           (and (some #{"java.lang.NoClassDefFoundError"} failure-classes)
                (some #{"java.lang.ClassNotFoundException"} failure-classes))
           {:class-eviction-repro/expected
            "unresolved sibling caller fails with NoClassDefFoundError caused by ClassNotFoundException"
            :class-eviction-repro/actual unresolved-invocation})
          (require-evidence!
           (= 42 (:class-eviction-repro/value resolved-after-clear))
           {:class-eviction-repro/expected
            "already-resolved sibling caller remains executable after cache clear"
            :class-eviction-repro/actual resolved-after-clear})
          (require-evidence!
           (not (.containsKey cache-after-lookup target-name))
           {:class-eviction-repro/expected
            "failed lookup removes the cleared cache entry"
            :class-eviction-repro/target-name target-name})
          (prn
           {:class-eviction-repro/event :class-eviction-repro/verdict
            :class-eviction-repro/cache-id (identity-id cache-after-lookup)
            :class-eviction-repro/cache-identical-throughout?
            (identical? cache-before cache-after-lookup)
            :class-eviction-repro/cache-key-present-after-lookup?
            (.containsKey cache-after-lookup target-name)
            :class-eviction-repro/unresolved-after-clear unresolved-invocation
            :class-eviction-repro/resolved-after-clear resolved-after-clear
            :class-eviction-repro/proven
            [:class-eviction-repro/no-cache-refresh-occurred
             :class-eviction-repro/cleared-soft-reference-breaks-unresolved-sibling-lookup
             :class-eviction-repro/already-resolved-link-remains-executable]
            :class-eviction-repro/not-proven
            [:class-eviction-repro/gc-cleared-the-2026-08-03-class
             :class-eviction-repro/datahike-target-lacked-strong-roots]})
          :class-eviction-repro/passed)))))

(defn -main
  [& _arguments]
  (require-evidence!
   (= "true" (vm-option "UseG1GC"))
   {:class-eviction-repro/expected "-XX:+UseG1GC"
    :class-eviction-repro/actual (vm-option "UseG1GC")})
  (require-evidence!
   (= "12.5" (vm-option "MaxRAMPercentage"))
   {:class-eviction-repro/expected "-XX:MaxRAMPercentage=12.5"
    :class-eviction-repro/actual (vm-option "MaxRAMPercentage")})
  (require-evidence!
   (= "30000" (vm-option "G1PeriodicGCInterval"))
   {:class-eviction-repro/expected "-XX:G1PeriodicGCInterval=30000"
    :class-eviction-repro/actual (vm-option "G1PeriodicGCInterval")})
  (run-probe!))

(apply -main *command-line-args*)
