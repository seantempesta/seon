(ns seon.order
  "Canonical structural order of whole values: the one comparator storage,
   identity sets and stable output sort by. Requires no `seon` namespace.")

(defn compare-values
  "Order whole values structurally without rendering their contents or invoking pairs."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Structural order compares arbitrary Clojure values, including nil, scalars and host objects.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Structural order compares arbitrary Clojure values, including nil, scalars and host objects.", :gen/elements [nil false 0 "" :k [] {}]}]] :int]}
  [left right]
  (letfn [(category [value]
            (cond (nil? value) "nil"
                  (map? value) "map"
                  (set? value) "set"
                  (vector? value) "vector"
                  (sequential? value) "list"
                  :else (str (type value))))
          (items [left right]
            (loop [left (seq left) right (seq right)]
              (cond (nil? left) (if (nil? right) 0 -1)
                    (nil? right) 1
                    :else (let [order (order-value (first left) (first right))]
                            (if (zero? order)
                              (recur (next left) (next right)) order)))))
          (order-value [left right]
            (let [kind (compare (category left) (category right))]
              (cond
                (not (zero? kind)) kind
                (identical? left right) 0
                (map? left) (items (sort-by key order-value left)
                                   (sort-by key order-value right))
                (set? left) (items (sort order-value left) (sort order-value right))
                (sequential? left) (items left right)
                :else (try (compare left right)
                           (catch #?(:clj ClassCastException :cljs :default) _ 0)))))]
    (order-value left right)))
