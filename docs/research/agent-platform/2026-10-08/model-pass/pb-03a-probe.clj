;; PB-03a probes (lane impl-pb03a, throwaway root at HEAD e7f120672). Driver: bb --config /dev/null --classpath "script:src:resources" tmp/pb03a/driver.clj FORM-FILE
;; driver = seon.operator/prepl-value! against the owned root (see base-miss-probes.clj). Results: pb-03a-notes.md.
;; Before p3: touch the root's src files (program classes are dated 2100, so require :reload loads the class and is a no-op).

;;; p1
(do
  (let [E (fn [s] (deref (ns-resolve 'seon.sci.eval s)))
        mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        tm (fn [label f] (System/gc)
             (let [b0 (.getCurrentThreadAllocatedBytes mx) t0 (System/nanoTime) r (f)
                   ms (/ (- (System/nanoTime) t0) 1e6) mb (/ (- (.getCurrentThreadAllocatedBytes mx) b0) 1e6)]
               [[label (Math/round ms) (Math/round mb)] r]))
        conn (seon.cluster.boot/connection "default")
        db (datahike.api/db conn)
        loaded ((E 'loaded-program) db)
        run (fn []
              (let [[a _] (tm :load-core-namespaces #((E 'load-core-namespaces!) loaded))
                    [b projection] (tm :projection-from-database
                                       #(if-let [c (seon.db/carried-projection loaded)]
                                          (seon.schema/projection-from-database loaded c)
                                          (seon.schema/projection-from-database loaded)))
                    [c ctx0] (tm :build-base-ctx #(seon.schema/call-with-projection projection (fn [] ((E 'build-base-ctx) projection))))
                    ctx (assoc ctx0 :seon.sci.kernel/install-function! (E 'install-function-from-database!))
                    _ (swap! (:seon.sci.kernel/program-snapshot ctx) assoc :seon.sci.eval/loaded-database loaded)
                    [d acq] (tm :acquire-program! #((E 'acquire-program!) {:seon.sci.eval/ctx ctx :seon.db/db loaded :seon.schema/projection projection}))
                    [e _] (tm :whole-derive-base-ctx #((E 'derive-base-ctx) loaded))]
                [a b c d e]))]
    {:commit (str ((E (quote commit-id-of)) loaded))
     :runs [(run) (run) (run)]
     :functions (count (:functions @(:seon.sci.kernel/program-snapshot ((E 'derive-base-ctx) loaded))))
     :base-kind (get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/base-context])}))

;;; p2
(do
  (let [E (fn [s] (deref (ns-resolve 'seon.sci.eval s)))
        conn (seon.cluster.boot/connection "default")
        loaded ((E 'loaded-program) (datahike.api/db conn))
        _ ((E 'derive-base-ctx) loaded)
        mark (seon.profile/begin)
        _ ((E 'derive-base-ctx) loaded)
        ex (seon.profile/explain mark 100000)]
    (->> (:seon.profile/growths ex) (sort-by :seon.profile/total-ms >) (take 18)
         (mapv (fn [m] [(:seon.profile/sym m) (:seon.profile/calls m) (:seon.profile/total-ms m)
                        (some-> (:seon.profile/allocated-bytes m) (/ 1e6) Math/round)])))))

;;; p3
(do
  (let [E (fn [s] (deref (ns-resolve 'seon.sci.eval s)))
        mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        tm (fn [f] (System/gc)
             (let [b0 (.getCurrentThreadAllocatedBytes mx) t0 (System/nanoTime) r (f)]
               [r (Math/round (/ (- (System/nanoTime) t0) 1e6)) (Math/round (/ (- (.getCurrentThreadAllocatedBytes mx) b0) 1e6))]))
        conn (seon.cluster.boot/connection "default")
        loaded ((E 'loaded-program) (datahike.api/db conn))
        base ((E 'derive-base-ctx) loaded)
        sci-var (fn [ctx ns-sym nm] (deref (get-in @(:env ctx) [:namespaces ns-sym nm])))
        [_ fork-ms fork-mb] (tm #(sci.core/fork base))
        rows-in (fn [ns-sym] (count (datahike.api/q '[:find [?f ...] :in $ ?n :where [?ns :seon.ns/name ?n] [?f :seon.fn/ns ?ns]] loaded ns-sym)))
        probe (fn [ns-sym pick]
                (let [stale-before (identical? (deref (find-var (symbol (str ns-sym) (str pick)))) (sci-var base ns-sym pick))
                      [_ rms rmb] (tm #(clojure.core/load-file (str "/Users/sean/src/seon/tmp/pb03a/root/data/source/e7f1206721cbb7f438b64d226cf45e447eebb804/src/" (-> (str ns-sym) (clojure.string/replace "." "/") (clojure.string/replace "-" "_")) ".clj")))
                      host (deref (find-var (symbol (str ns-sym) (str pick))))
                      stale-after (not (identical? host (sci-var base ns-sym pick)))
                      interns (ns-interns (find-ns ns-sym))
                      [_ cms cmb] (tm #(let [sn (sci.core/create-ns ns-sym)
                                              f (sci.core/fork base)]
                                          (sci.core/add-namespace! f ns-sym (into {} (map (fn [[k v]] [k ((E 'copy-host-var) v sn)])) interns))
                                          f))]
                  {:ns ns-sym :rows (rows-in ns-sym) :interns (count interns)
                   :reload-ms rms :reload-mb rmb
                   :base-var-identical-before-reload stale-before :base-stale-after-reload stale-after
                   :advance-recopy-ms cms :advance-recopy-mb cmb}))]
    {:fork-ms fork-ms :fork-mb fork-mb
     :probes [(probe (quote seon.id) (quote printed)) (probe (quote my.task) (first (sort (keys (ns-publics (quote my.task)))))) (probe (quote seon.task) (quote assignment)) (probe (quote seon.fn) (quote reverse-closure))]}))
