;;; Probe: does a BOOTED CLUSTER arm every contract its program declares?
;;;
;;; Run in a live cluster JVM, e.g. mcp__seon__eval_clj mode "jvm". The
;;; expected set is derived here with MALLI'S OWN two rules rather than by
;;; calling `seon.instrument/armable`, so this is an independent check of the
;;; gate's parity assertion and not a restatement of it:
;;;   `mi/-schema`      — a `:malli/schema`, or a complete set of arglist ones
;;;   primitive-fn      — reference-code/malli/src/malli/instrument.clj:16,24
;;;
;;; Measured 2026-09-08 on the live `default` cluster:
;;;   {:program-namespaces 91 :cluster-loaded-ns 518 :cluster-instrumented 812
;;;    :armable-in-program 820 :armed-outside-program 2
;;;    :armable-not-armed ["seon.edit.jvm/edit" "seon.fs.jvm/glob"
;;;                        "seon.fs.jvm/read" "seon.fs.jvm/read-complete"
;;;                        "seon.fs.jvm/stat" "seon.fs.jvm/write"
;;;                        "seon.shell.jvm/run"
;;;                        "seon.test.runner/run-coordinator!"
;;;                        "seon.web.jvm/fetch" "seon.web.jvm/search"]}
;;; docs/seon/issues/a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md
(do
  (require '[malli.instrument :as mi] '[clojure.java.io :as io] '[clojure.set :as set])
  (let [program
        (into []
              (keep (fn [^java.io.File f]
                      (when (and (.isFile f)
                                 (or (.endsWith (.getName f) ".clj")
                                     (.endsWith (.getName f) ".cljc")))
                        (with-open [s (java.io.PushbackReader. (io/reader f))]
                          (let [form (read {:read-cond :allow :eof nil} s)]
                            (when (and (seq? form) (= 'ns (first form)))
                              (second form)))))))
              (file-seq (io/file "src")))
        primitive?
        (fn [v]
          (and (fn? v)
               (boolean (some (fn [^Class c]
                                (.startsWith (.getName c) "clojure.lang.IFn$"))
                              (supers (class v))))))
        armable (into #{}
                      (comp (keep find-ns)
                            (mapcat ns-interns)
                            (map val)
                            (filter (fn [c]
                                      (and (mi/-schema c)
                                           (bound? c)
                                           (not (primitive? (deref c)))))))
                      program)
        instrumented (seon.instrument/instrumented)
        sym (fn [v] (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v)))))]
    {:cwd (.getCanonicalPath (io/file "."))
     :program-namespaces (count program)
     :program-namespaces-unloaded (sort (remove find-ns program))
     :cluster-loaded-ns (count (all-ns))
     :cluster-instrumented (count instrumented)
     :armable-in-program (count armable)
     :armable-not-armed (sort (map sym (set/difference armable instrumented)))
     :armed-outside-program
     (count (remove #(contains? (set program) (ns-name (:ns (meta %))))
                    instrumented))}))
