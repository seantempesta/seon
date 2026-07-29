#!/usr/bin/env bb
;; px — send ONE form to a cluster's io-prepl and print the value.
;;
;;   tmp/repl-experiments/px <cluster> '<form>'
;;   tmp/repl-experiments/px --port 64567 '<form>'
;;   echo '<form>' | tmp/repl-experiments/px <cluster>
;;
;; Cluster names resolve through the advertisement, searching BOTH roots:
;; data/clusters (the operator's) and tmp/repl-experiments/clusters (scratch).
;; A stale advertisement (dead pid) resolves to nothing and says so, so a
;; cluster that "looks live" in a directory listing can never be probed by
;; accident.
;;
;; Values print as EDN; :out and :err events print with a prefix; an
;; exception prints its :cause and the FIRST FIVE trace frames only —
;; a full prepl exception map is thousands of tokens of context tax.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.net Socket InetSocketAddress])

(def roots ["data/clusters" "tmp/repl-experiments/clusters"])

(defn advertisement [cluster]
  (some (fn [root]
          (let [f (io/file root cluster "prepl.edn")]
            (when (.exists f)
              (try (assoc (edn/read-string (slurp f)) ::root root)
                   (catch Exception _ nil)))))
        roots))

(defn live? [{:keys [:seon.boot/pid]}]
  (and pid (.isPresent (java.lang.ProcessHandle/of (long pid)))))

(defn resolve-port [args]
  (if (= "--port" (first args))
    [(parse-long (second args)) (drop 2 args)]
    (let [cluster (first args)
          ad (advertisement cluster)]
      (cond
        (nil? ad) (binding [*out* *err*]
                    (println "px: no advertisement for" cluster "under" roots)
                    (System/exit 2))
        (not (live? ad)) (binding [*out* *err*]
                           (println "px: STALE advertisement for" cluster
                                    "- pid" (:seon.boot/pid ad) "is gone")
                           (System/exit 2))
        :else [(:seon.boot/prepl-port ad) (rest args)]))))

(defn trim-exception [v]
  (let [m (try (edn/read-string v) (catch Exception _ nil))]
    (if (and (map? m) (:cause m))
      (pr-str {:cause (:cause m)
               :phase (:phase m)
               :trace (vec (take 5 (:trace m)))
               :elided (max 0 (- (count (:trace m)) 5))})
      v)))

(let [[port args] (resolve-port *command-line-args*)
      form (if (seq args) (str/join " " args) (slurp *in*))
      sock (doto (Socket.) (.connect (InetSocketAddress. "127.0.0.1" (int port)) 3000))
      w (io/writer sock)
      r (java.io.PushbackReader. (io/reader sock))]
  (.write w (str form "\n"))
  (.flush w)
  (loop []
    (let [ev (edn/read {:eof ::eof :default (fn [_ v] v)} r)]
      (when-not (= ::eof ev)
        (case (:tag ev)
          :ret (do (println (if (:exception ev) (trim-exception (:val ev)) (:val ev)))
                   (System/exit (if (:exception ev) 1 0)))
          :out (do (print (:val ev)) (flush) (recur))
          :err (do (binding [*out* *err*] (print (:val ev)) (flush)) (recur))
          (recur))))))
