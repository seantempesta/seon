;; (3) the call graph drawn with each node's bound at a chosen input and the total at its root,
;; through the one printer; an edit of a bound (its fit) is the next draw's input, nothing cached
(def lab-graph {'seon.context/world [:build :advance :page0 :page1] 'design/turn [:patch :changes]})
(defn lab-mb [b] (str (Math/round (/ (:ticks b) 1000.0)) "k ticks, " (/ (Math/round (/ (:bytes b) 1e5)) 10.0) " MB"))
(defn lab-graph-hiccup [s]
  (into [:ul]
        (for [[root ks] lab-graph]
          (let [bs (mapv #(lab-piece-bound % s) ks) total (apply merge-with + bs)]
            [:li [:b (str root)] " " (lab-mb total)
             (into [:ul] (map (fn [k b] [:li (name k) " " (lab-mb b)]) ks bs))]))))
