(require '[sci.core :as sci] '[seon.db :as db] '[sci.interrupt])
(def ns-obj (sci/create-ns 'seon.db))
;; A: sci/copy-var of the HOST dynamic var
(def ctx (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}}))
(sci/add-namespace! ctx 'seon.db
  (assoc (ns-publics 'seon.db) '*conn* (sci/copy-var db/*conn* ns-obj)))
(sci/add-namespace! ctx 'host {'peek (sci/copy-var* #'clojure.core/identity ns-obj)})
;; host probe fn: a COMPILED fn that reads the compiled var
(defn host-peek [] db/*conn*)
(sci/add-namespace! ctx 'host {'peek (sci/copy-var* #'host-peek ns-obj)})
(defn p [l code] (println l (try (pr-str (sci/eval-string* ctx code)) (catch Throwable t (str "THREW: " (ex-message t))))))
(p "sci-side deref default:" "seon.db/*conn*")
(p "sci binding, sci read:" "(binding [seon.db/*conn* :SCI-VALUE] seon.db/*conn*)")
(p "sci binding, COMPILED read:" "(binding [seon.db/*conn* :SCI-VALUE] (host/peek))")
(p "host binding visible to sci?:" "(host/peek)")
(println "host binding, sci read:"
  (binding [db/*conn* :HOST-VALUE] (sci/eval-string* ctx "seon.db/*conn*")))
;; B: sci/new-dynamic-var (pure sci var, no host counterpart)
(def ctx2 (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core
                                  'my {'*c* (sci/new-dynamic-var '*c* :DEFAULT-B ns-obj)}}}))
(println "pure sci dynvar:" (sci/eval-string* ctx2 "(binding [my/*c* 7] my/*c*)"))
;; C: is a sci-resolved var captured at ANALYSIS time into a closure?
(def ctx3 (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}}))
(sci/eval-string* ctx3 "(def base 1) (defn f [] base) (def g f)")
(sci/eval-string* ctx3 "(def base 2)")
(println "redef-through-closure:" (sci/eval-string* ctx3 "[(f) (g)]"))
(def f4 (sci/eval-string* ctx3 "(fn [] base)"))
(sci/eval-string* ctx3 "(def base 3)")
(println "captured-fn-value-after-redef:" (f4))
;; does that fn still work in a DIFFERENT ctx?
(def ctx4 (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}}))
(sci/eval-string* ctx4 "(def base :CTX4)")
(println "fn-from-ctx3-called-under-ctx4:" (try (pr-str (f4)) (catch Throwable t (ex-message t))))
;; D: fork sharing of a var object
(def ctx5 (sci/init {}))
(sci/eval-string* ctx5 "(def shared 1)")
(def forked (sci/fork ctx5))
(sci/eval-string* forked "(def shared 99)")
(println "fork-original-after-fork-redef:" (sci/eval-string* ctx5 "shared"))
