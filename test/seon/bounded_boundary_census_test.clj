(ns seon.bounded-boundary-census-test
  "Recurring source census for bounded locks and foreign subprocesses."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.zip :as z]))

;; These are ownership roots, not a maintained member roster. Every boundary
;; form beneath them is derived from syntax on each run.
(def ^:private production-roots
  ["resources/seon/operator"
   "src/seon/operator.clj"
   "src/seon/cluster/export.clj"
   "src/seon/cluster/registry.clj"
   "src/seon/shell/jvm.clj"
   "script/seon/dev"
   "script/seon/fresh_operator.clj"])

(def ^:private lifecycle-lock-symbols
  '#{seon.operator.state/with-control-lock!
     seon.operator.state/with-lifecycle-lock!})

(def ^:private subprocess-seam-symbol
  'seon.operator.state/run-process!)

(def ^:private babashka-process-symbols
  '#{babashka.process/check babashka.process/process babashka.process/sh
     babashka.process/shell})

(def ^:private process-method-symbols
  '#{.get .getErrorStream .getInputStream .waitFor})

(def ^:private process-constructor-symbols
  '#{ProcessBuilder. java.lang.ProcessBuilder.})

(defn- clojure-source?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- source-files
  [roots]
  (let [files
        (into []
              (comp (map io/file)
                    (mapcat #(if (.isDirectory ^java.io.File %)
                               (file-seq %)
                               [%]))
                    (filter clojure-source?)
                    (map #(.getPath ^java.io.File %))
                    (distinct))
              roots)]
    (when-not (seq files)
      (throw
       (ex-info "The bounded-boundary census found no source files."
                {:seon.error/kind
                 :seon.bounded-boundary-census/no-source-subjects
                 :seon.bounded-boundary-census/roots roots})))
    (sort files)))

(defn- require-aliases
  [form]
  (if (= 'ns (first form))
    (into {}
          (keep
           (fn [entry]
             (when (and (vector? entry) (symbol? (first entry)))
               (when-let [as-index
                          (first
                           (keep-indexed
                            (fn [index value]
                              (when (= :as value) index))
                            entry))]
                 (let [local-alias (get entry (inc as-index))]
                   (when (symbol? local-alias)
                     [local-alias (first entry)]))))))
          (tree-seq coll? seq form))
    {}))

(defn- canonical-symbol
  [aliases value]
  (if (and (symbol? value) (namespace value))
    (if-let [target (get aliases (symbol (namespace value)))]
      (symbol (str target) (name value))
      value)
    value))

(defn- enclosing-function
  [location]
  (loop [parent (z/up location)]
    (when parent
      (if (and (= :list (z/tag parent)) (z/sexpr-able? parent))
        (let [form (z/sexpr parent)]
          (if (and (contains? '#{defn defn-} (first form))
                   (symbol? (second form)))
            (second form)
            (recur (z/up parent))))
        (recur (z/up parent))))))

(defn- zipper-forms
  [path]
  (loop [location
         (z/of-file* path {:track-position? true
                           :auto-resolve #(or % 'source)})
         forms []]
    (if (z/end? location)
      forms
      (let [forms
            (if (and (= :list (z/tag location)) (z/sexpr-able? location))
              (conj forms
                    {:seon.bounded-boundary-census/form (z/sexpr location)
                     :seon.bounded-boundary-census/line
                     (first (z/position location))
                     :seon.bounded-boundary-census/owner
                     (enclosing-function location)})
              forms)]
        (recur (z/next location) forms)))))

(defn- source-forms
  [path]
  (let [forms (zipper-forms path)
        aliases
        (reduce
         (fn [result {form :seon.bounded-boundary-census/form}]
           (merge result (require-aliases form)))
         {}
         forms)]
    (mapv
     (fn [{form :seon.bounded-boundary-census/form :as subject}]
       (assoc subject
              :seon.bounded-boundary-census/file path
              :seon.bounded-boundary-census/head
              (canonical-symbol aliases (first form))))
     forms)))

(defn- map-declares-bounds?
  [value bound-keys]
  (and (map? value) (every? #(some? (get value %)) bound-keys)))

(defn- lifecycle-lock-subject
  [{form :seon.bounded-boundary-census/form
    head :seon.bounded-boundary-census/head
    owner :seon.bounded-boundary-census/owner
    path :seon.bounded-boundary-census/file
    :as subject}]
  (when (contains? lifecycle-lock-symbols head)
    (let [forwarding-seam?
          (and (= path "resources/seon/operator/state.clj")
               (= owner 'with-control-lock!)
               (= head 'seon.operator.state/with-lifecycle-lock!))
          request (if (= head 'seon.operator.state/with-control-lock!)
                    (nth form 2 nil)
                    (second form))
          bounded?
          (or forwarding-seam?
              (map-declares-bounds?
               request
               [:seon.operator.lock/acquisition-timeout-ms
                :seon.operator.lock/hold-timeout-ms]))]
      (assoc subject
             :seon.bounded-boundary-census/class :lifecycle-lock
             :seon.bounded-boundary-census/disposition
             (if bounded? :bounded :defect)))))

(defn- process-constructor?
  [form head]
  (or (contains? process-constructor-symbols head)
      (and (= 'new head)
           (contains? '#{ProcessBuilder java.lang.ProcessBuilder}
                      (second form)))))

(defn- process-owner-keys
  [forms]
  (into #{}
        (keep
         (fn [{form :seon.bounded-boundary-census/form
               head :seon.bounded-boundary-census/head
               owner :seon.bounded-boundary-census/owner
               path :seon.bounded-boundary-census/file}]
           (when (and owner
                      (or (process-constructor? form head)
                          (contains? babashka-process-symbols head)
                          (= '.waitFor head)
                          (= '.onExit head)))
             [path owner])))
        forms))

(defn- network-owner-keys
  [forms]
  (into #{}
        (keep
         (fn [{head :seon.bounded-boundary-census/head
               owner :seon.bounded-boundary-census/owner
               path :seon.bounded-boundary-census/file}]
           (when (and owner
                      (or (contains? '#{Socket. java.net.Socket.
                                        ServerSocket. java.net.ServerSocket.}
                                      head)
                          (contains? '#{.accept .connect .setSoTimeout} head)))
             [path owner])))
        forms))

(defn- exact-subprocess-seam-internal?
  [path owner head form]
  (or
   (and (= path "resources/seon/operator/state.clj")
        (= owner 'run-process!)
        (or (= head 'babashka.process/process)
            (and (= head '.waitFor) (= 4 (count form)))))
   (and (= path "resources/seon/operator/state.clj")
        (contains? '#{terminate-recorded-process! terminate-subprocess!} owner)
        (= head '.get)
        (= 4 (count form)))
   (and (= path "src/seon/shell/jvm.clj")
        (or (and (= owner 'execute)
                 (= head 'babashka.process/process))
            (and (= owner 'terminate-tree!)
                 (= head '.waitFor)
                 (= 4 (count form)))
            (and (contains? '#{await-exit terminate-tree!} owner)
                 (= head '.get)
                 (= 4 (count form)))))
   (and (= path "script/seon/fresh_operator.clj")
        (= owner 'record-launched-process!)
        (= head '.get)
        (= 4 (count form)))))

(defn- on-exit-get?
  [form]
  (let [awaited (second form)]
    (and (seq? awaited) (= '.onExit (first awaited)))))

(defn- subprocess-subject
  [process-owners network-owners
   {form :seon.bounded-boundary-census/form
    head :seon.bounded-boundary-census/head
    owner :seon.bounded-boundary-census/owner
    path :seon.bounded-boundary-census/file
    :as subject}]
  (let [seam-call? (= subprocess-seam-symbol head)
        process-call? (contains? babashka-process-symbols head)
        process-method?
        (and (contains? process-method-symbols head)
             (or (and (= '.get head)
                      (or (on-exit-get? form)
                          (exact-subprocess-seam-internal?
                           path owner head form)))
                 (= '.waitFor head)
                 (= '.getErrorStream head)
                 (and (= '.getInputStream head)
                      (not (contains? network-owners [path owner]))
                      (contains? process-owners [path owner]))))
        subject? (or seam-call? process-call? process-method?)]
    (when subject?
      (assoc subject
             :seon.bounded-boundary-census/class :foreign-subprocess
             :seon.bounded-boundary-census/disposition
             (cond
               seam-call?
               (if (map-declares-bounds?
                    (second form) [:seon.operator.subprocess/deadline-ms])
                 :bounded
                 :defect)

               (exact-subprocess-seam-internal? path owner head form) :bounded
               :else :defect)))))

(defn- boundary-census
  [roots]
  (let [files (source-files roots)
        forms (mapv identity (mapcat source-forms files))
        process-owners (process-owner-keys forms)
        network-owners (network-owner-keys forms)
        subjects
        (into []
              (keep #(or (lifecycle-lock-subject %)
                         (subprocess-subject process-owners network-owners %)))
              forms)]
    (when-not (seq subjects)
      (throw
       (ex-info "The bounded-boundary census found no boundary subjects."
                {:seon.error/kind
                 :seon.bounded-boundary-census/no-boundary-subjects
                 :seon.bounded-boundary-census/files files})))
    subjects))

(defn- defects
  [subjects]
  (filterv #(= :defect (:seon.bounded-boundary-census/disposition %))
           subjects))

(defn- delete-tree!
  [root]
  (doseq [file (reverse (file-seq root))]
    (.delete ^java.io.File file)))

(defn- with-source
  [source f]
  (let [root (io/file "tmp" (str "bounded-boundary-census-"
                                  (random-uuid)))
        _ (.mkdirs root)
        file (io/file root "fixture.clj")]
    (try
      (spit file source)
      (f (.getPath root))
      (finally
        (delete-tree! root)))))

(deftest every-lock-and-foreign-subprocess-boundary-declares-a-bound
  (let [subjects (boundary-census production-roots)
        by-class (group-by :seon.bounded-boundary-census/class subjects)
        found-defects (defects subjects)]
    (testing "the source-derived census is subject-present on both classes"
      (is (seq (:lifecycle-lock by-class)) (pr-str subjects))
      (is (seq (:foreign-subprocess by-class)) (pr-str subjects)))
    (testing "new direct acquisitions and waits cannot omit their bound"
      (is (empty? found-defects) (pr-str found-defects)))))

(deftest census-refuses-absent-roots-and-absent-subjects
  (let [absent (str (io/file "tmp" (str "absent-boundary-census-"
                                        (random-uuid))))
        absent-error
        (try (boundary-census [absent]) nil
             (catch clojure.lang.ExceptionInfo error error))]
    (is (= :seon.bounded-boundary-census/no-source-subjects
           (:seon.error/kind (ex-data absent-error))))
    (with-source
      "(ns fixture.no-boundaries)\n(def answer 42)\n"
      (fn [root]
        (let [error
              (try (boundary-census [root]) nil
                   (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= :seon.bounded-boundary-census/no-boundary-subjects
                 (:seon.error/kind (ex-data error)))))))))

(deftest synthetic-unbounded-forms-are-classified-as-defects
  (doseq [[label source expected-class]
          [[:lock
            "(ns fixture.lock (:require [seon.operator.state :as state]))\n(defn f [] (state/with-lifecycle-lock! {:seon.operator.lock/path \"x\" :seon.operator.lock/acquisition-timeout-ms 10} identity))\n"
            :lifecycle-lock]
           [:subprocess-seam
            "(ns fixture.seam (:require [seon.operator.state :as state]))\n(defn f [] (state/run-process! {:seon.operator.subprocess/argv [\"true\"]}))\n"
            :foreign-subprocess]
           [:timed-direct-wait
            "(ns fixture.wait)\n(defn f [p] (.waitFor p 1 java.util.concurrent.TimeUnit/SECONDS))\n"
            :foreign-subprocess]
           [:unbounded-on-exit
            "(ns fixture.on-exit)\n(defn f [p] (.get (.onExit p)))\n"
            :foreign-subprocess]
           [:direct-output-read
            "(ns fixture.read)\n(defn f [] (let [p (.start (ProcessBuilder. ^java.util.List [\"true\"]))] (.getInputStream p)))\n"
            :foreign-subprocess]]]
    (testing (name label)
      (with-source
        source
        (fn [root]
          (let [found (defects (boundary-census [root]))]
            (is (some #(= expected-class
                          (:seon.bounded-boundary-census/class %))
                      found)
                (pr-str found))))))))

(deftest synthetic-declared-calls-are-bounded
  (with-source
    (str "(ns fixture.bounded (:require [seon.operator.state :as state]))\n"
         "(defn f []\n"
         "  (state/with-lifecycle-lock!\n"
         "   {:seon.operator.lock/path \"x\"\n"
         "    :seon.operator.lock/acquisition-timeout-ms 10\n"
         "    :seon.operator.lock/hold-timeout-ms 10}\n"
         "   #(state/run-process!\n"
         "     {:seon.operator.subprocess/argv [\"true\"]\n"
         "      :seon.operator.subprocess/deadline-ms 10})))\n")
    (fn [root]
      (is (empty? (defects (boundary-census [root])))))))
