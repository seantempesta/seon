;; Census of host sites that read or write a Var's binding (deref, meta, root
;; writes, test-var) in src/, with the enclosing definition and whether the
;; site sits lexically inside a `with-ctx`. Run: bb <this file> [src-root]
;; An op not in head position (threaded or passed as a value) prints as `op*`
;; with the enclosing form as its operand. Prints EDN rows; classification of which operands are SCI Vars is manual.
(require '[rewrite-clj.zip :as z] '[rewrite-clj.node :as n] '[clojure.java.io :as io]
         '[clojure.string :as str])

(def ops
  '#{deref meta alter-meta! reset-meta! var-get alter-var-root test-var
     clojure.test/test-var ct/test-var t/test-var
     .getRawRoot .hasRoot .bindRoot .isMacro
     sci/bind-root! sci.core/bind-root! sci/intern sci.core/intern sci/alter-var-root
     sci/var->symbol vars/getRawRoot vars/bindRoot sci.impl.vars/getRawRoot})

(defn head [zloc]
  (when (= :list (z/tag zloc))
    (let [f (z/down zloc)] (when (and f (= :token (z/tag f))) (z/sexpr f)))))

(defn with-ctx-ancestor? [zloc]
  (loop [p (z/up zloc)]
    (cond (nil? p) false
          (let [h (head p)] (and (symbol? h) (str/ends-with? (name h) "with-ctx"))) true
          :else (recur (z/up p)))))

(defn top-name [zloc]
  (loop [p zloc]
    (let [u (z/up p)]
      (if (or (nil? u) (= :forms (z/tag u)))
        (let [f (z/down p)] (some-> f z/right z/sexpr str))
        (recur u)))))

(defn operand [zloc kind]
  (let [s (cond (= kind :deref) (some-> zloc z/down z/string)
                (str/ends-with? (str kind) "*") (some-> zloc z/up z/string)
                :else (some-> zloc z/down z/right z/string))]
    (when s (let [s (str/replace s #"\s+" " ")] (subs s 0 (min 70 (count s)))))))

(defn sites [file]
  (let [root (z/of-string (slurp file) {:track-position? true})]
    (loop [loc root acc []]
      (if (z/end? loc)
        acc
        (let [kind (cond (= :deref (z/tag loc)) :deref
                         :else (let [h (head loc)] (when (and (symbol? h) (ops h)) h)))
              ;; an op passed as a value or threaded (`(some-> v deref)`, `(comp :test meta)`)
              kind (or kind
                       (when (and (= :token (z/tag loc)) (not (z/leftmost? loc)))
                         (let [t (z/sexpr loc)] (when (and (symbol? t) (ops t)) (symbol (str t "*"))))))
              acc (if kind
                    (conj acc {:file (str file) :line (first (z/position loc)) :op kind
                               :in (try (top-name loc) (catch Exception _ "?"))
                               :operand (operand loc kind)
                               :with-ctx? (with-ctx-ancestor? loc)})
                    acc)]
          (recur (z/next loc) acc))))))

(let [src (or (first *command-line-args*) "src")]
  (doseq [f (sort (map str (filter #(re-find #"\.cljc?$" (str %)) (file-seq (io/file src)))))
          row (sites f)]
    (prn row)))
