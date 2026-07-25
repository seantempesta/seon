(ns attack.proto
  "ATTACK 7 -- extend-protocol / defrecord against a BASE protocol, plus the
   same-name-in-two-forks race for protocols and records."
  (:require [flow.eval :as eval]
            [sci.core :as sci]
            [sci.interrupt :as sci-interrupt]))

(defn ev [source]
  (:flow/value (eval/evaluate {:source source :db nil :time-limit-ms 3000
                               :allocation-limit-bytes (* 128 1024 1024)})))

(defn -main [& _]
  (eval/open! 4)

  (println "\n--- 7a. protocol defined in a FORK: isolated?")
  (println "    A:" (pr-str (ev "(do (defprotocol Greet (hi [x])) (extend-protocol Greet String (hi [s] (str \"hi \" s))) (hi \"a\"))")))
  (println "    B (fresh fork) resolves Greet?" (pr-str (ev "(resolve 'Greet)")))
  (println "    B (fresh fork) resolves hi?   " (pr-str (ev "(resolve 'hi)")))

  (println "\n--- 7b. extend-protocol onto a BASE protocol (deliberately-unsafe base)")
  (let [base (sci/init {:namespaces {'clojure.core sci-interrupt/clojure-core}})
        _ (sci/eval-form base (read-string "(defprotocol Render (render [x]))"))
        run (fn [src] (try (sci/eval-form (sci/fork base) (read-string src))
                           (catch Throwable t [:threw (.getMessage t)])))]
    (println "    B before A:" (pr-str (run "(render \"s\")")))
    (println "    A extends it for String:" (pr-str (run "(do (extend-protocol Render String (render [s] [:from-A s])) :ok)")))
    (println "    B (fresh fork) now:" (pr-str (run "(render \"s\")"))))

  (println "\n--- 7c. extend-protocol against a HOST class from a fork -- global JVM effect?")
  (println "    A:" (pr-str (ev "(do (defprotocol P (p [x])) (extend-protocol P java.lang.Long (p [n] (* n 2))) (p 21))")))
  (println "    B (fresh fork):" (pr-str (ev "(resolve 'p)")))

  (println "\nOK")
  (System/exit 0))
