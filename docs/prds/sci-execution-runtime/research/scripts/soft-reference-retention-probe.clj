#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns soft-reference-retention-probe
  (:import
   (clojure.asm ClassWriter Opcodes)
   (clojure.lang DynamicClassLoader RT)
   (com.sun.management HotSpotDiagnosticMXBean)
   (java.lang.management ManagementFactory)
   (java.lang.ref SoftReference WeakReference)
   (java.util ArrayList UUID)))

(defn- emit-constructor!
  [^ClassWriter writer]
  (let [method (.visitMethod writer Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)]
    (.visitCode method)
    (.visitVarInsn method Opcodes/ALOAD 0)
    (.visitMethodInsn method Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
    (.visitInsn method Opcodes/RETURN)
    (.visitMaxs method 1 1)
    (.visitEnd method)))

(defn- callee-bytes
  [internal-name]
  (let [writer (ClassWriter. 0)
        method-name "value"]
    (.visit writer Opcodes/V11 Opcodes/ACC_PUBLIC internal-name nil "java/lang/Object" nil)
    (emit-constructor! writer)
    (let [method (.visitMethod writer
                               (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                               method-name
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
  [internal-name callee-internal-name]
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
      (.visitMethodInsn method Opcodes/INVOKESTATIC callee-internal-name "value" "()I" false)
      (.visitInsn method Opcodes/IRETURN)
      (.visitMaxs method 1 0)
      (.visitEnd method))
    (.visitEnd writer)
    (.toByteArray writer)))

(defn- class-cache
  []
  (let [field (.getDeclaredField DynamicClassLoader "classCache")]
    (.setAccessible field true)
    (.get field nil)))

(defn- probe-classes
  []
  (let [suffix (str (.replace (str (UUID/randomUUID)) "-" ""))
        package-name "seon.probe.dynamic"
        callee-name (str package-name ".Callee" suffix)
        caller-name (str package-name ".Caller" suffix)
        callee-internal-name (.replace callee-name "." "/")
        caller-internal-name (.replace caller-name "." "/")
        callee-loader (DynamicClassLoader. (RT/baseLoader))
        callee-class (.defineClass callee-loader
                                   callee-name
                                   (callee-bytes callee-internal-name)
                                   nil)
        callee-loader-reference (WeakReference. callee-loader)
        callee-class-reference (WeakReference. callee-class)
        cache-reference ^SoftReference (.get (class-cache) callee-name)
        caller-loader (DynamicClassLoader. (RT/baseLoader))
        caller-class (.defineClass caller-loader
                                   caller-name
                                   (caller-bytes caller-internal-name callee-internal-name)
                                   nil)
        caller-method (.getMethod caller-class "call" (make-array Class 0))]
    {:soft-reference-retention-probe/cache-reference cache-reference
     :soft-reference-retention-probe/callee-class-reference callee-class-reference
     :soft-reference-retention-probe/callee-loader-reference callee-loader-reference
     :soft-reference-retention-probe/callee-name callee-name
     :soft-reference-retention-probe/caller-class caller-class
     :soft-reference-retention-probe/caller-loader caller-loader
     :soft-reference-retention-probe/caller-method caller-method}))

(defn- force-gc!
  []
  (dotimes [_ 4]
    (System/gc)
    (System/runFinalization)))

(defn- moderate-pressure!
  []
  (let [runtime (Runtime/getRuntime)
        used-bytes (- (.totalMemory runtime) (.freeMemory runtime))
        available-bytes (- (.maxMemory runtime) used-bytes)
        target-bytes (long (* 0.45 available-bytes))
        chunks (ArrayList.)]
    (loop [allocated 0]
      (when (< allocated target-bytes)
        (.add chunks (byte-array (* 1024 1024)))
        (recur (+ allocated (* 1024 1024)))))
    (force-gc!)
    (.clear chunks)))

(defn- exhaustion-pressure!
  []
  (let [chunks (ArrayList.)
        allocated-mib (long-array 1)]
    (try
      (loop []
        (.add chunks (byte-array (* 1024 1024)))
        (aset-long allocated-mib 0 (inc (aget allocated-mib 0)))
        (recur))
      (catch OutOfMemoryError _
        nil))
    (.clear chunks)
    (force-gc!)
    (aget allocated-mib 0)))

(defn- cause-chain
  [throwable]
  (loop [causes []
         cause throwable]
    (if cause
      (recur (conj causes (.getName (class cause))) (.getCause cause))
      causes)))

(defn- invoke-caller
  [method]
  (try
    {:soft-reference-retention-probe/invocation-result
     (.invoke method nil (object-array 0))}
    (catch Throwable throwable
      {:soft-reference-retention-probe/invocation-causes
       (cause-chain throwable)})))

(defn- vm-option
  [option-name]
  (let [server (ManagementFactory/getPlatformMBeanServer)
        diagnostic-bean (ManagementFactory/newPlatformMXBeanProxy
                         server
                         "com.sun.management:type=HotSpotDiagnostic"
                         HotSpotDiagnosticMXBean)]
    (.getValue (.getVMOption diagnostic-bean option-name))))

(defn- pressure!
  [pressure]
  (case pressure
    "gc-only" (do (force-gc!) nil)
    "moderate" (do (moderate-pressure!) nil)
    "exhaustion" (exhaustion-pressure!)
    (throw (ex-info "Unknown pressure profile."
                    {:soft-reference-retention-probe/pressure pressure}))))

(defn- run-probe
  [pressure age-ms]
  (let [{::keys [cache-reference
                 callee-class-reference
                 callee-loader-reference
                 callee-name
                 caller-class
                 caller-loader
                 caller-method]}
        (probe-classes)
        _ (when (pos? age-ms)
            (Thread/sleep age-ms))
        runtime (Runtime/getRuntime)
        used-before-pressure-mib
        (quot (- (.totalMemory runtime) (.freeMemory runtime)) (* 1024 1024))
        exhaustion-allocated-mib (pressure! pressure)
        cache-map (class-cache)
        before-invocation
        {:soft-reference-retention-probe/cache-key-present?
         (.containsKey cache-map callee-name)
         :soft-reference-retention-probe/cache-referent-alive?
         (some? (.get ^SoftReference cache-reference))
         :soft-reference-retention-probe/callee-class-alive?
         (some? (.get ^WeakReference callee-class-reference))
         :soft-reference-retention-probe/callee-loader-alive?
         (some? (.get ^WeakReference callee-loader-reference))}
        invocation (invoke-caller caller-method)]
    (prn
     {:soft-reference-retention-probe/java-version
      (System/getProperty "java.version")
      :soft-reference-retention-probe/max-heap-mib
      (quot (.maxMemory (Runtime/getRuntime)) (* 1024 1024))
      :soft-reference-retention-probe/soft-ref-lru-policy-ms-per-mb
      (vm-option "SoftRefLRUPolicyMSPerMB")
      :soft-reference-retention-probe/pressure pressure
      :soft-reference-retention-probe/age-ms age-ms
      :soft-reference-retention-probe/used-before-pressure-mib
      used-before-pressure-mib
      :soft-reference-retention-probe/exhaustion-allocated-mib
      exhaustion-allocated-mib
      :soft-reference-retention-probe/before-invocation before-invocation
      :soft-reference-retention-probe/invocation invocation
      :soft-reference-retention-probe/caller-class-loader
      (str (.getClassLoader ^Class caller-class))
      :soft-reference-retention-probe/caller-loader-retained?
      (identical? caller-loader (.getClassLoader ^Class caller-class))
      :soft-reference-retention-probe/cache-key-present-after-invocation?
      (.containsKey cache-map callee-name)})))

(let [[pressure age-ms] *command-line-args*]
  (run-probe (or pressure "gc-only")
             (if age-ms (Long/parseLong age-ms) 0)))
