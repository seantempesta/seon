(ns seon.shell
  "Pure process request predicates.")

(defn stdin?
  "True when stdin names exactly one byte source."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.shell/stdin-text
                    :my.shell/stdin-bytes
                    :seon.blob/digest])))))


(defn output?
  "True when output names exactly one complete representation."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.shell.output/text
                    :my.shell.output/octet-values
                    :my.shell.output/blob])))))

