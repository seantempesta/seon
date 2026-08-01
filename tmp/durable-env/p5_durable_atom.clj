;;; p5 — the owner's atom-protocol idea, tested directly:
;;;   (a) can sci run on a CUSTOM IAtom/IDeref env at all? (sci/init accepts
;;;       :env — impl/opts.cljc:255 `(or env (atom {}))`)
;;;   (b) what does a durable-backed swap! cost per turn?
;;;   (c) is @env even serializable? (it holds sci.lang.Var objects)
;;; Run: clojure -M:dev -i tmp/durable-env/p5_durable_atom.clj
(require '[sci.core :as sci]
         '[konserve.filestore :as fs]
         '[konserve.core :as k]
         '[clojure.java.io :as io])
(import '[java.util.concurrent.atomic AtomicReference])

(defn line [& xs] (println (apply str xs)))
(defn ms [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))

(def root "tmp/durable-env/store-p5")
(defn rm-rf [p] (let [f (io/file p)]
                  (when (.exists f) (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c)))))
(rm-rf root)
(def store (fs/connect-fs-store root :opts {:sync? true}))

(line "\n=== A. a custom IAtom as sci's :env — does sci even run on it? ===")
(def swap-count (atom 0))
(def persist-count (atom 0))
(def persist-fn (atom (fn [_v] nil)))

(deftype DurableRef [^AtomicReference cell]
  clojure.lang.IDeref
  (deref [_] (.get cell))
  clojure.lang.IAtom
  (swap [_ f]
    (swap! swap-count inc)
    (let [old (.get cell) nv (f old)]
      (.set cell nv)
      (when-not (identical? old nv) (swap! persist-count inc) (@persist-fn nv))
      nv))
  (swap [_ f a]
    (swap! swap-count inc)
    (let [old (.get cell) nv (f old a)]
      (.set cell nv)
      (when-not (identical? old nv) (swap! persist-count inc) (@persist-fn nv))
      nv))
  (swap [_ f a b]
    (swap! swap-count inc)
    (let [old (.get cell) nv (f old a b)]
      (.set cell nv)
      (when-not (identical? old nv) (swap! persist-count inc) (@persist-fn nv))
      nv))
  (swap [_ f a b more]
    (swap! swap-count inc)
    (let [old (.get cell) nv (apply f old a b more)]
      (.set cell nv)
      (when-not (identical? old nv) (swap! persist-count inc) (@persist-fn nv))
      nv))
  (reset [_ v] (.set cell v) v)
  (compareAndSet [_ o n] (.compareAndSet cell o n)))

(def durable-env (DurableRef. (AtomicReference. {})))
(def ctx (try (sci/init {:env durable-env :namespaces {'user {}}})
              (catch Throwable t (line "  sci/init THREW: " (.getMessage t)) nil)))
(line "  sci/init on a custom IAtom: " (if ctx "OK" "FAILED"))
(when ctx
  (line "  (:env ctx) class: " (class (:env ctx)))
  (line "  eval works: " (sci/eval-string* ctx "(ns my.d) (def a 1) (defn f [x] (* 2 x)) (f 21)"))
  (line "  swaps so far: " @swap-count "  VALUE-CHANGING swaps: " @persist-count))

(line "\n=== B. how many swaps CHANGE the env value? (the redefinition case) ===")
(when ctx
  (reset! swap-count 0) (reset! persist-count 0)
  (sci/eval-string* ctx "(in-ns 'my.d) (def a 2)")
  (line "  REdefine an existing name: swaps=" @swap-count " value-changing=" @persist-count)
  (line "  but the value IS now: " (sci/eval-string* ctx "(in-ns 'my.d) a"))
  (reset! swap-count 0) (reset! persist-count 0)
  (sci/eval-string* ctx "(in-ns 'my.d) (def brand-new 1)")
  (line "  define a NEW name        : swaps=" @swap-count " value-changing=" @persist-count))

(line "\n=== C. IS @env SERIALIZABLE AT ALL? ===")
(when ctx
  (let [envv @(:env ctx)]
    (line "  @env keys: " (pr-str (sort (keys envv))))
    (line "  whole @env -> konserve: "
          (try (k/assoc store :env envv {:sync? true}) "OK"
               (catch Throwable t (str "REFUSED: " (.getMessage t)))))
    (line "  just (:namespaces @env) -> konserve: "
          (try (k/assoc store :nss (:namespaces envv) {:sync? true}) "OK"
               (catch Throwable t (str "REFUSED: " (.getMessage t)))))
    (line "  one namespace's TABLE (my.d) -> konserve: "
          (try (k/assoc store :one (get (:namespaces envv) 'my.d) {:sync? true}) "OK"
               (catch Throwable t (str "REFUSED: " (.getMessage t)))))
    (line "  the DEREFED data value only: "
          (try (k/assoc store :dv @(get (get (:namespaces envv) 'my.d) 'a) {:sync? true}) "OK"
               (catch Throwable t (str "REFUSED: " (.getMessage t)))))))

(line "\n=== D. per-swap cost if each value-changing swap persisted ===")
(when ctx
  ;; Bound the projection to the AGENT namespace only (the whole env can't
  ;; serialize at all — §C), and persist a name->edn projection.
  (reset! persist-fn
          (fn [envv]
            (let [nsm (dissoc (get (:namespaces envv) 'my.d) :obj)
                  proj (into {} (for [[nm v] nsm
                                      :let [val (try @v (catch Throwable _ ::unbound))]
                                      :when (not (fn? val))]
                                  [(str nm) (pr-str val)]))]
              (k/assoc store :proj proj {:sync? true}))))
  (reset! swap-count 0) (reset! persist-count 0)
  (let [n 50
        [t _] (ms #(dotimes [i n]
                     (sci/eval-string* ctx (format "(in-ns 'my.d) (def v%d %d)" i i))))]
    (line (format "  %d defs with a durable-persisting env: %.1f ms total, %.2f ms/def (%d persists)"
                  n t (/ t n) @persist-count)))
  ;; control: a plain atom env
  (let [ctx2 (sci/init {:namespaces {'user {}}})
        _ (sci/eval-string* ctx2 "(ns my.d)")
        n 50
        [t _] (ms #(dotimes [i n]
                     (sci/eval-string* ctx2 (format "(in-ns 'my.d) (def v%d %d)" i i))))]
    (line (format "  %d defs with a PLAIN atom env:        %.1f ms total, %.2f ms/def"
                  n t (/ t n)))))

(line "\n=== E. does a plain atom's swap! actually change the value on redefinition? ===")
(let [ctx3 (sci/init {:namespaces {'user {}}})]
  (sci/eval-string* ctx3 "(ns my.z) (def q 1)")
  (let [e (:env ctx3)
        before @e]
    (sci/eval-string* ctx3 "(in-ns 'my.z) (def q 2)")
    (line "  @env identical? across a redefinition: " (identical? before @e))
    (line "  value read back: " (sci/eval-string* ctx3 "(in-ns 'my.z) q"))
    (line "  => a value-diffing durable atom sees NOTHING for the redefinition.")))

(System/exit 0)
