#!/usr/bin/env bb
;; Minimal io-prepl client for the bench cluster.
;;   bb tmp/bench/prepl.clj <port> <file-with-forms>
;; Prints every prepl message; exits non-zero if any :tag is :err/:ret with
;; :exception true.
(require '[clojure.edn :as edn] '[clojure.java.io :as io])

(let [[port file] *command-line-args*
      port (Integer/parseInt port)
      source (slurp file)
      socket (java.net.Socket. "127.0.0.1" port)
      out (io/writer socket)
      in (java.io.PushbackReader. (io/reader socket))]
  (.write out source)
  (.write out "\n:seon.bench/done\n")
  (.flush out)
  (loop [failed? false]
    (let [message (try (edn/read {:eof ::eof :default (fn [_ v] v)} in)
                       (catch Exception e {:tag :err :val (str e)}))]
      (cond
        (= ::eof message) (do (.close socket) (System/exit (if failed? 1 0)))
        :else
        (let [{:keys [tag val exception]} message]
          (case tag
            :out (do (print val) (flush))
            :err (binding [*out* *err*] (print val) (flush))
            :ret (println "=>" (if exception
                                 (str "EXCEPTION "
                                      (or (when (map? val) (:cause val))
                                          (subs (str val) 0 (min 400 (count (str val))))))
                                 val))
            (println (pr-str message)))
          (if (and (= tag :ret) (= val ":seon.bench/done"))
            (do (.close socket) (System/exit (if failed? 1 0)))
            (recur (or failed? (boolean exception)))))))))
