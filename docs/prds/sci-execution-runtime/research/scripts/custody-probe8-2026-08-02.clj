(require '[sci.core :as sci] '[sci.interrupt] '[seon.db :as db] '[sci.impl.utils :as u] '[sci.impl.vars :as v])
(defn mk [] (let [c (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}})]
              (sci/add-namespace! c 'seon.db (ns-interns 'seon.db)) c))
;; 1. confirm the fork redefinition leak precisely
(def p (mk))
(sci/eval-string* p "(defn f [] :PARENT)")
(def fk (sci/fork p))
(sci/eval-string* fk "(defn f [] :CHANGED-IN-FORK)")
(println "1a parent after fork redef:" (sci/eval-string* p "(f)"))
(sci/eval-string* fk "(defn brand-new [] :FORK-ONLY)")
(println "1b parent sees fork-only def:" (try (pr-str (sci/eval-string* p "(brand-new)")) (catch Throwable t (str "ABSENT: " (ex-message t)))))
;; 2. is the same Var OBJECT shared across the fork?
(println "2 identical Var object across fork:"
  (identical? (get-in @(:env p) [:namespaces 'user 'f]) (get-in @(:env fk) [:namespaces 'user 'f])))
;; 3. what does a def over an installed clojure.lang.Var do?
(def h (mk))
(def host-var-before (get-in @(:env h) [:namespaces 'seon.db 'q]))
(println "3a installed q class:" (type host-var-before))
(println "3b sci/utils var? on clojure.lang.Var:" (u/var? host-var-before))
(sci/eval-string* h "(in-ns 'seon.db) (def q (fn [& _] :SHADOW)) (in-ns 'user)")
(def host-var-after (get-in @(:env h) [:namespaces 'seon.db 'q]))
(println "3c env entry class AFTER def:" (type host-var-after))
(println "3d host clojure Var untouched?:" (identical? host-var-before #'seon.db/q) "| still fn?:" (fn? @#'seon.db/q))
;; 4. fork cost on a realistic env
(def big (mk))
(dotimes [i 400] (sci/eval-string* big (str "(defn gen" i " [] " i ")")))
(println "4a namespaces:" (count (:namespaces @(:env big))) "user interns:" (count (get-in @(:env big) [:namespaces 'user])))
(let [t0 (System/nanoTime) n 100000]
  (dotimes [_ n] (sci/fork big))
  (println "4b sci/fork ns per call:" (/ (- (System/nanoTime) t0) (double n))))
;; 5. does bindRoot bypass eval-def? (Seon's install-function-contract! path)
(def b (mk))
(sci/eval-string* b "(defn g [] :ORIG)")
(def bf (sci/fork b))
(v/bindRoot (sci/resolve bf 'user/g) (fn [] :BINDROOT-IN-FORK))
(println "5 parent after bindRoot in fork:" (sci/eval-string* b "(g)"))
;; 6. can interpreted code swap! the env atom -> which protocol?
(def s (mk))
(sci/add-namespace! s 'holder {'e (sci/new-var 'e (:env s) {:ns (sci/create-ns 'holder)})})
(println "6a swap! env:" (try (pr-str (sci/eval-string* s "(do (swap! holder/e identity) :ok)")) (catch Throwable t (ex-message t))))
(println "6b deref env:" (try (count (:namespaces (sci/eval-string* s "@holder/e"))) (catch Throwable t (ex-message t))))
