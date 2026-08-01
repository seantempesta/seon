;;; p1 — the sci env atom's exact anatomy, and whether swap! on it is a
;;; usable persistence hook. Run: clojure -M:dev -i tmp/durable-env/p1_env_anatomy.clj
(require '[sci.core :as sci])

(defn line [& xs] (println (apply str xs)))

(def ctx (sci/init {:namespaces {'user {}}}))
(def env (:env ctx))

(line "\n=== A. what the ctx map is ===")
(line "ctx class: " (class ctx))
(line "ctx keys : " (pr-str (sort (keys ctx))))
(line ":env is  : " (class env))
(line "@env class: " (class @env))
(line "@env keys : " (pr-str (sort (keys @env))))

(line "\n=== B. per-key value classes and sizes ===")
(doseq [k (sort (keys @env))]
  (let [v (get @env k)]
    (line (format "  %-16s %-46s %s"
                  (str k) (str (class v))
                  (if (map? v) (str (count v) " entries") "")))))

(line "\n=== C. one namespace's entry shape ===")
(let [nss (:namespaces @env)
      user (get nss 'user)]
  (line "namespaces count: " (count nss))
  (line "'user entry class: " (class user) " keys: " (pr-str (keys user))))

(sci/eval-string* ctx "(ns my.probe) (def d {:a 1}) (defn f [x] (* 2 x)) (def g (fn [x] x))")
(let [nsm (get (:namespaces @env) 'my.probe)]
  (doseq [[k v] (sort-by (comp str key) nsm)]
    (line (format "  %-10s var-class=%-18s root-class=%s"
                  (str k) (str (class v))
                  (if (instance? sci.lang.Var v) (str (class @v)) "-")))))

(line "\n=== D. IS swap! ON THE ENV ATOM A USABLE HOOK? ===")
;; D1: a FIRST def of a new name
(def observed (atom []))
(add-watch env :probe (fn [_ _ old new]
                        (swap! observed conj
                               {:identical-namespaces? (identical? (:namespaces old) (:namespaces new))
                                :identical-env? (identical? old new)})))
(reset! observed [])
(sci/eval-string* ctx "(in-ns 'my.probe) (def fresh 1)")
(line "first def of a NEW name — watch fires: " (count @observed) " " (pr-str @observed))

;; D2: REDEFINITION of an existing name (the case that matters for a session)
(reset! observed [])
(sci/eval-string* ctx "(in-ns 'my.probe) (def fresh 2)")
(line "REdef of an EXISTING name  — watch fires: " (count @observed) " " (pr-str @observed))
(line "  value now: " (sci/eval-string* ctx "(in-ns 'my.probe) fresh"))

;; D3: is the namespaces map structurally changed by a redefinition?
(let [before (:namespaces @env)
      _ (sci/eval-string* ctx "(in-ns 'my.probe) (def fresh 3)")
      after (:namespaces @env)]
  (line "  (:namespaces env) identical across a redefinition? "
        (identical? before after))
  (line "  = (value equality) across a redefinition?          "
        (= before after))
  (line "  the Var OBJECT identical across a redefinition?    "
        (identical? (get (get before 'my.probe) 'fresh)
                    (get (get after 'my.probe) 'fresh)))
  (line "  the DEREFED value after: " (sci/eval-string* ctx "(in-ns 'my.probe) fresh")))
(remove-watch env :probe)

(line "\n=== E. where the value actually lives: the Var's mutable root ===")
(let [v (get (get (:namespaces @env) 'my.probe) 'fresh)]
  (line "Var fields: " (pr-str (mapv #(.getName ^java.lang.reflect.Field %)
                                     (.getDeclaredFields sci.lang.Var))))
  (line "deref: " @v))

(line "\n=== F. sci Vars support WATCHES (notify-watches, lang.cljc:97-101) ===")
(let [hits (atom [])
      v (get (get (:namespaces @env) 'my.probe) 'fresh)]
  (try
    (add-watch v :probe (fn [k r o n] (swap! hits conj [o n])))
    (sci/eval-string* ctx "(in-ns 'my.probe) (def fresh 99)")
    (line "add-watch on a sci Var, then redefine -> hits: " (pr-str @hits))
    (catch Throwable t
      (line "add-watch on a sci Var THREW: " (.getMessage t) " (" (class t) ")"))))

(line "\n=== G. how many swap!s does one ordinary turn cost? ===")
(let [ctx2 (sci/init {:namespaces {'user {}}})
      n (atom 0)]
  (add-watch (:env ctx2) :count (fn [_ _ _ _] (swap! n inc)))
  (sci/eval-string* ctx2 "(ns my.t)")
  (let [after-ns @n]
    (sci/eval-string* ctx2 "(def a 1)")
    (let [after-def @n]
      (sci/eval-string* ctx2 "(defn h [x] (inc x))")
      (let [after-defn @n]
        (sci/eval-string* ctx2 "(+ 1 2)")
        (let [after-pure @n]
          (sci/eval-string* ctx2 "(require '[clojure.string :as s])")
          (line "  (ns my.t)           swaps: " after-ns)
          (line "  (def a 1)           swaps: " (- after-def after-ns))
          (line "  (defn h [x] ...)    swaps: " (- after-defn after-def))
          (line "  (+ 1 2)             swaps: " (- after-pure after-defn))
          (line "  (require ...)       swaps: " (- @n after-pure))
          (line "  TOTAL for 5 forms:  " @n))))))

(line "\n=== H. how big is the env value? (namespaces map, entry counts) ===")
(let [nss (:namespaces @env)]
  (line "  namespaces: " (count nss))
  (line "  total interned names: " (reduce + (map (comp count val) nss)))
  (line "  clojure.core alone:   " (count (get nss 'clojure.core))))

(System/exit 0)
