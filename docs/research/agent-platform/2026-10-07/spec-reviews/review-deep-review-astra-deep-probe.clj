(let [printer (fn [data ^java.io.Writer writer]
  (binding [*out* writer *print-length* nil *print-level* nil *print-meta* false *print-dup* false
            *print-readably* true *print-namespace-maps* true]
    (let [vector-method (get-method print-method clojure.lang.IPersistentVector)
          seq-method (get-method print-method clojure.lang.ISeq)
          set-method (get-method print-method clojure.lang.IPersistentSet)
          map-method (get-method print-method clojure.lang.IPersistentMap)
          ;; `clojure.core/lift-ns` (core_print.clj:247-261)
          lift-ns (fn [m]
                    (loop [space nil
                           [[k v :as entry] & entries] (seq m)
                           kvs []]
                      (if entry
                        (when (and (qualified-ident? k) (or (nil? space) (= space (namespace k))))
                          (recur (namespace k) entries
                                 (conj kvs [(if (symbol? k) (symbol nil (name k)) (keyword nil (name k))) v])))
                        [space kvs])))
          ;; a frame is [sep close items started? entries?], advanced in place
          frame (fn [x]
                  (condp identical? (get-method print-method
                                                (let [t (get (meta x) :type)] (if (keyword? t) t (class x))))
                    vector-method (do (.write writer "[") (object-array [" " "]" (seq x) false false]))
                    seq-method (do (.write writer "(") (object-array [" " ")" (seq x) false false]))
                    set-method (do (.write writer "#{") (object-array [" " "}" (seq x) false false]))
                    map-method (let [[space kvs] (lift-ns x)]
                                 (.write writer (if space (str "#:" space "{") "{"))
                                 (object-array [", " "}" (seq (if space kvs x)) false true]))
                    nil))
          stack (java.util.ArrayDeque.)]
      (loop [x data entry? false visit? true]
        (if visit?
          (if-some [open (if entry? (object-array [" " "" (seq x) false false]) (frame x))]
            (do (.push stack open) (recur nil false false))
            (do (print-method x writer) (recur nil false false)))
          (when-some [^objects top (.peek stack)]
            (if-some [items (aget top 2)]
              (do (when (aget top 3) (.write writer ^String (aget top 0)))
                  (aset top 2 (next items))
                  (aset top 3 true)
                  (recur (first items) (aget top 4) true))
              (do (.write writer ^String (aget top 1))
                  (.pop stack)
                  (recur nil false false))))))))
  nil)
 print-new (fn [x] (let [w (java.io.StringWriter.)] (printer x w) (str w)))
 print-old (fn [x] (binding [*print-length* nil *print-level* nil *print-meta* false *print-dup* false *print-readably* true *print-namespace-maps* true] (pr-str x)))
 shallow [nil true false [] () {} #{} (sorted-map :b 1 :a 2) (array-map :a/b 1 :a/c 2)
  (hash-map :a/b 1 :a/c 2) (sorted-map :a/b 1 :a/c 2) (sorted-set 3 1 2) #{:x :a :b}
  {(symbol "odd ns" "x/y") 1} {(keyword "odd ns" "x/y") 1} {:a/b 1 :c/d 2}
  (symbol "hello world") (keyword "x y") (symbol "/") (symbol "a" "/")
  (with-meta [1 2] {:type :review-deep/unknown :x 1}) (with-meta 'abc {:tag 'String})
  \a \newline \space \tab \backspace \formfeed \return (char 0) (char 0xD800)
  "a\\b\n\r\t\f\b\"z" "😀" 0 -1 1/3 1.23M 123N (biginteger "123") ##NaN ##Inf ##-Inf (float ##NaN)
  (java.util.regex.Pattern/compile "a\\s+\"") (java.util.Date. 0) (java.util.UUID/fromString "00000000-0000-0000-0000-000000000000")
  (tagged-literal 'foo {:a/b [1 2]}) (Object.) (java.util.ArrayList. [1 2]) (java.util.LinkedList. [1 2])
  (doto (java.util.LinkedHashMap.) (.put :a/b [1 2])) (java.util.HashSet. [1 2])
  (map inc [1 2 3]) (range 3) (first {:a 1}) (with-meta {:x/y 1} {:type :review-deep/unknown})]
 deep (reduce (fn [v _] [v]) [] (range 10000))
 deep-cases [[:vector deep] [:map-key (array-map deep 1)]
             [:metadata (with-meta deep {:type :review-deep/unknown})]
             [:tagged (tagged-literal 'foo deep)] [:java-list (java.util.ArrayList. [deep])]
             [:java-map (doto (java.util.LinkedHashMap.) (.put :a deep))]]
 outcome (fn [f x] (let [t (System/nanoTime)] (try {:length (count (f x)) :ms (/ (- (System/nanoTime) t) 1e6)} (catch Throwable e {:error (.getName (class e)) :ms (/ (- (System/nanoTime) t) 1e6)}))))]
 {:shallow-count (count shallow)
  :differences (into [] (keep-indexed (fn [i v] (let [a (print-old v) b (print-new v)] (when (not= a b) {:i i :class (str (class v)) :old a :new b})))) shallow)
  :depth (mapv (fn [[label x]] [label (outcome print-new x)]) deep-cases)})
