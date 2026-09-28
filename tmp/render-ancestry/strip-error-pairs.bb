;; Remove the copied seon.error render pair from schemas of the form
;; [:and ... :seon.error/base ...]: in the :and's own properties and in its
;; direct [:map {...} ...] children's properties. Plain [:map ...] schemas keep
;; theirs. Prints per-file counts. Usage: bb strip-error-pairs.bb <files...>
(require '[rewrite-clj.zip :as z] '[rewrite-clj.node :as n] '[clojure.string :as str])

(def pair '{:seon.render/ai seon.error/render-ai :seon.render/html seon.error/render-html})

(defn ws? [node] (or (n/whitespace-or-comment? node) (n/comma? node)))
(defn sig [node] (when-not (ws? node) (try (n/sexpr node) (catch Exception _ ::unreadable))))

(defn strip-map
  "Children of a map node without the copied pair entries, and how many went."
  [children]
  (loop [out [] pending children removed 0]
    (if-let [c (first pending)]
      (if (ws? c)
        (recur (conj out c) (rest pending) removed)
        (let [[between after] (split-with ws? (rest pending))
              v (first after)]
          (if (and v (= (get pair (sig c) ::none) (sig v)))
            ;; drop the whitespace before the key, the key, what lies between and the value
            (let [out' (vec (reverse (drop-while ws? (reverse out))))]
              (recur out' (rest after) (inc removed)))
            (recur (-> out (conj c) (into between) (conj v)) (rest after) removed))))
      [(vec (drop-while ws? out)) removed])))

(defn blank-children? [children] (every? ws? children))

(defn strip-props
  "Strip the map node at index `i` of `children` if it is one; drop it when empty."
  [children i]
  (let [m (get children i)]
    (if (and m (= :map (n/tag m)))
      (let [[kids removed] (strip-map (n/children m))]
        (if (zero? removed)
          [children 0]
          (if (blank-children? kids)
            ;; remove the empty map and the whitespace after it
            (let [after (drop (inc i) children)]
              [(into (subvec children 0 i) (drop-while ws? after)) removed])
            [(assoc children i (n/map-node kids)) removed])))
      [children 0])))

(defn first-sig-index [children k]
  (->> (keep-indexed (fn [i c] (when-not (ws? c) i)) children) (drop k) first))

(defn strip-and [node]
  (let [children (vec (n/children node))
        sigs (keep sig children)]
    (if (and (= :and (first sigs)) (some #{:seon.error/base} sigs))
      (let [;; own properties: the :and's second significant child
            [children r1] (strip-props children (first-sig-index children 1))
            ;; direct [:map {...}] children
            [children r2] (reduce (fn [[cs total] i]
                                    (let [c (get cs i)]
                                      (if (and (= :vector (n/tag c))
                                               (= :map (sig (first (remove ws? (n/children c))))))
                                        (let [inner (vec (n/children c))
                                              [inner r] (strip-props inner (first-sig-index inner 1))]
                                          [(assoc cs i (n/vector-node inner)) (+ total r)])
                                        [cs total])))
                                  [children 0] (range (count children)))]
        [(n/vector-node children) (+ r1 r2)])
      [node 0])))

(doseq [file *command-line-args*]
  (let [zloc (z/of-string (slurp file) {:track-position? false})
        [root total]
        (loop [loc zloc total 0]
          (if (z/end? loc)
            [(z/root loc) total]
            (if (= :vector (z/tag loc))
              (let [[node r] (strip-and (z/node loc))]
                (recur (z/next (if (pos? r) (z/replace loc node) loc)) (+ total r)))
              (recur (z/next loc) total))))]
    (when (pos? total)
      (spit file (n/string root))
      (println file total))))
