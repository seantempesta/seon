;; bb tmp/heap-retention/is_message_sweep.clj [--write] FILE...
;; A clojure.test `is` evaluates its message on every assertion, passing ones
;; included, so `(is form (pr-str value))` prints `value` unbounded each time
;; (pid 3360 OOM, 2026-09-27: a 2 GB string of a load result holding the whole
;; schema projection). Rewrites every one-argument `pr-str` inside an `is`
;; message to `seon.print.value/bounded-text` and requires `seon.print.value`.
;; A multi-argument pr-str bounds its arguments as one vector.
(require '[rewrite-clj.zip :as z] '[rewrite-clj.node :as n] '[clojure.string :as str])

(defn head [loc] (when (= :list (z/tag loc)) (some-> (z/down loc) (#(when (= :token (z/tag %)) (z/sexpr %))))))

(defn fix-message [msg skipped]
  (z/subedit-> msg
               (z/prewalk (fn [l] (= 'pr-str (head l)))
                          (fn [l] (if (= 1 (count (rest (z/child-sexprs l))))
                                    (z/up (z/replace (z/down l) (n/token-node 'seon.print.value/bounded-text)))
                                    ;; (pr-str a b) prints both: bound them as one vector
                                    (z/replace l (n/list-node
                                                  [(n/token-node 'seon.print.value/bounded-text) (n/spaces 1)
                                                   (n/vector-node (interpose (n/spaces 1) (rest (filter (complement n/whitespace-or-comment?) (n/children (z/node l))))))])))))))

(defn require-print-value
  "The file's ns form with `[seon.print.value]` appended to its :require."
  [text]
  (let [ns-form (z/find (z/of-string text {:track-position? true}) z/next #(= 'ns (head %)))
        req (z/find (z/down ns-form) z/right #(= :require (head %)))
        column (second (z/position (z/rightmost (z/down req))))]
    (z/root-string (-> req
                       (z/append-child* (n/newlines 1))
                       (z/append-child* (n/spaces (dec column)))
                       (z/append-child* (n/vector-node [(n/token-node 'seon.print.value)]))))))

(let [[flag & files] *command-line-args*
      write? (= flag "--write")
      files (if write? files (cons flag files))
      total (atom 0)]
  (doseq [f files]
    (let [skipped (atom [])
          changed (atom 0)
          out (loop [l (z/of-string (slurp f))]
                (if (z/end? l)
                  (z/root-string l)
                  (if-let [msg (and ('#{is clojure.test/is t/is test/is} (head l)) (some-> l z/down z/right z/right))]
                    (let [msg' (if (z/find msg z/next #(= 'pr-str (head %))) (fix-message msg skipped) msg)]
                      (when (not= (z/string msg) (z/string msg')) (swap! changed inc))
                      (recur (z/next msg')))
                    (recur (z/next l)))))
          out (if (and (pos? @changed) (not (re-find #"\[seon\.print\.value[\] ]" out)))
                (require-print-value out)
                out)]
      (when (pos? @changed)
        (swap! total + @changed)
        (println f @changed (if (seq @skipped) (str "SKIPPED " @skipped) ""))
        (when write? (spit f out)))))
  (println "total" @total))
