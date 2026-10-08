(require '[clojure.edn :as edn] '[clojure.java.io :as io])
(let [ad (edn/read-string (slurp "tmp/defn-cost3/root/data/clusters/default/prepl.edn"))]
  (with-open [s (java.net.Socket. (:seon.boot/prepl-host ad) (:seon.boot/prepl-port ad))
              w (io/writer s) r (java.io.PushbackReader. (io/reader s))]
    (.setSoTimeout s 120000)
    (.write w (str (slurp (first *command-line-args*)) "\n")) (.flush w)
    (loop [] (let [e (edn/read {:eof nil} r)]
               (if (= :ret (:tag e)) (println (:val e))
                   (when e (binding [*out* *err*] (println e)) (recur)))))))
