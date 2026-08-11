(require '[sci.core :as sci])

(def base-context (sci/init {}))

(defn- aliases
  [n]
  (into {}
        (map (fn [index]
               [(symbol (str "a" index)) 'clojure.core]))
        (range n)))

(defn- defining-source
  [index]
  (str "(def d" index " (fn [x] (+ x " index ")))"))

(defn- trial
  [alias-count definition-count mode]
  (let [ctx (sci/fork base-context)]
    (sci/install-namespace-bindings!
     ctx 'user
     {:aliases (aliases alias-count)
      :imports {}
      :requires #{}
      :refers {}})
    (case mode
      :facts
      (doseq [index (range definition-count)]
        (sci/intern ctx 'user (symbol (str "d" index)) index))

      :source
      (do
        ;; This mirrors Seon's current two-pass desk restore: establish every
        ;; Var before evaluating the stored defining forms.
        (doseq [index (range definition-count)]
          (sci/intern ctx 'user (symbol (str "d" index))))
        (doseq [index (range definition-count)]
          (sci/eval-string* ctx (defining-source index)))))
    ctx))

(defn- sample
  [alias-count definition-count mode iterations]
  (dotimes [_ 20]
    (trial alias-count definition-count mode))
  (let [microseconds
        (vec
         (repeatedly
          iterations
          (fn []
            (let [started (System/nanoTime)]
              (trial alias-count definition-count mode)
              (/ (double (- (System/nanoTime) started)) 1000.0)))))
        ordered (sort microseconds)]
    {:aliases alias-count
     :defs definition-count
     :mode mode
     :iterations iterations
     :median-us (nth ordered (quot iterations 2))
     :p95-us (nth ordered
                   (min (dec iterations) (int (* 0.95 iterations))))
     :mean-us (/ (reduce + microseconds) iterations)}))

(println (pr-str {:java-version (System/getProperty "java.version")}))
(doseq [arguments [[0 0 :facts 1000]
                   [10 0 :facts 1000]
                   [100 0 :facts 500]
                   [10 10 :facts 1000]
                   [25 50 :facts 500]
                   [100 100 :facts 300]
                   [10 10 :source 300]
                   [25 50 :source 100]
                   [100 100 :source 50]]]
  (println (pr-str (apply sample arguments))))
