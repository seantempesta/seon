(ns seon.render.markdown.parse
  "Zero-dependency pure-Clojure CommonMark + GFM AST parser, safe URL classifier,
   and strict HTML/SVG Hiccup sanitizer. Never emits raw HTML."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private punct (set "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"))

(defn- punct? [c] (boolean (and c (contains? punct c))))

(defn- ws? [c] (or (nil? c) (= c \space) (= c \tab) (= c \newline)))

(defn- flanking [before after]
  (let [next-ws (ws? after)
        next-pu (punct? after)
        prev-ws (ws? before)
        prev-pu (punct? before)]
    [(and (not next-ws) (or (not next-pu) prev-ws prev-pu))
     (and (not prev-ws) (or (not prev-pu) next-ws next-pu))]))

(defn- run-len [s i c]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n) (= (get s j) c)) (recur (inc j)) (- j i)))))

(defn- scan-code-span [s i]
  (let [n (run-len s i \`)
        open-end (+ i n)
        slen (count s)]
    (loop [j open-end]
      (cond
        (>= j slen) nil
        (= (get s j) \`)
        (let [m (run-len s j \`)]
          (if (= m n)
            (let [raw (str/replace (subs s open-end j) "\n" " ")
                  content (if (and (> (count raw) 1)
                                   (= \space (first raw))
                                   (= \space (last raw))
                                   (not (every? #(= % \space) raw)))
                            (subs raw 1 (dec (count raw)))
                            raw)]
              [{:t :code :v content} (+ j m)])
            (recur (+ j m))))
        :else (recur (inc j))))))

(defn- scan-angle [s i]
  (let [tail (subs s i)]
    (if-let [m (re-find #"^<https?://[^<>\x00-\x20]+>" tail)]
      (let [url (subs m 1 (dec (count m)))]
        [{:t :link :href url :children [{:t :str :v url}]} (+ i (count m))])
      (if-let [m (re-find #"^<mailto:[^<>\x00-\x20]+>" tail)]
        (let [url (subs m 1 (dec (count m)))]
          [{:t :link :href url :children [{:t :str :v (subs url 7)}]} (+ i (count m))])
        (when-let [m (re-find #"^<[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+>" tail)]
          (let [addr (subs m 1 (dec (count m)))]
            [{:t :link :href (str "mailto:" addr) :children [{:t :str :v addr}]} (+ i (count m))]))))))

(defn- match-bracket [s open]
  (let [n (count s)]
    (loop [j (inc open) depth 1]
      (cond
        (>= j n) nil
        (= (get s j) \\) (recur (+ j 2) depth)
        (= (get s j) \`) (if-let [[_ ni] (scan-code-span s j)]
                            (recur ni depth)
                            (recur (+ j (run-len s j \`)) depth))
        (= (get s j) \[) (recur (inc j) (inc depth))
        (= (get s j) \]) (if (= depth 1) j (recur (inc j) (dec depth)))
        :else (recur (inc j) depth)))))

(defn- skip-ws [s i]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n) (ws? (get s j))) (recur (inc j)) j))))

(defn- read-destination [s i]
  (let [n (count s)]
    (if (and (< i n) (= (get s i) \<))
      (when-let [close (str/index-of s ">" i)]
        [(subs s (inc i) close) (inc close)])
      (loop [j i depth 0]
        (let [c (get s j)]
          (cond
            (or (nil? c) (ws? c)) (when (>= j i) [(subs s i j) j])
            (= c \() (recur (inc j) (inc depth))
            (= c \)) (if (zero? depth) [(subs s i j) j] (recur (inc j) (dec depth)))
            :else (recur (inc j) depth)))))))

(defn- read-title [s i]
  (let [c (get s i)
        n (count s)]
    (if (contains? #{\" \' \(} c)
      (let [close (if (= c \() \) c)
            end (loop [j (inc i)]
                  (cond
                    (>= j n) nil
                    (= (get s j) \\) (recur (+ j 2))
                    (= (get s j) close) j
                    :else (recur (inc j))))]
        (if end [(subs s (inc i) end) (inc end)] [nil i]))
      [nil i])))

(defn- coalesce-strs [nodes]
  (reduce (fn [acc n]
            (if (and (= :str (:t n)) (seq acc) (= :str (:t (peek acc))))
              (conj (pop acc) {:t :str :v (str (:v (peek acc)) (:v n))})
              (conj acc n)))
          []
          nodes))

(defn- scan-link [s i image? parse-fn]
  (when-let [close (match-bracket s i)]
    (let [text (subs s (inc i) close)
          n (count s)]
      (when (and (< (inc close) n) (= (get s (inc close)) \())
        (let [p (skip-ws s (+ close 2))]
          (when-let [[dest dp] (read-destination s p)]
            (let [[title tp] (read-title s (skip-ws s dp))
                  p3 (skip-ws s tp)]
              (when (and (< p3 n) (= (get s p3) \)))
                (let [node (if image?
                             (cond-> {:t :image :src dest :alt text}
                               title (assoc :title title))
                             (cond-> {:t :link :href dest :children (parse-fn text)}
                               title (assoc :title title)))]
                  [node (inc p3)])))))))))

(defn- tokenize [s parse-fn]
  (let [n (count s)]
    (loop [i 0 buf [] out []]
      (let [flush-buf (fn [o] (if (seq buf) (conj o {:t :str :v (apply str buf)}) o))]
        (if (>= i n)
          (flush-buf out)
          (let [c (get s i)]
            (cond
              (and (= c \\) (< (inc i) n) (punct? (get s (inc i))))
              (recur (+ i 2) (conj buf (get s (inc i))) out)

              (and (= c \\) (< (inc i) n) (= (get s (inc i)) \newline))
              (recur (+ i 2) [] (conj (flush-buf out) {:t :br}))

              (= c \`)
              (if-let [[node ni] (scan-code-span s i)]
                (recur ni [] (conj (flush-buf out) node))
                (let [r (run-len s i \`)]
                  (recur (+ i r) (into buf (repeat r \`)) out)))

              (and (= c \!) (< (inc i) n) (= (get s (inc i)) \[))
              (if-let [[node ni] (scan-link s (inc i) true parse-fn)]
                (recur ni [] (conj (flush-buf out) node))
                (recur (inc i) (conj buf \!) out))

              (= c \[)
              (if-let [[node ni] (scan-link s i false parse-fn)]
                (recur ni [] (conj (flush-buf out) node))
                (recur (inc i) (conj buf \[) out))

              (= c \<)
              (if-let [[node ni] (scan-angle s i)]
                (recur ni [] (conj (flush-buf out) node))
                (recur (inc i) (conj buf \<) out))

              (or (= c \*) (= c \_))
              (let [r (run-len s i c)
                    before (when (> i 0) (get s (dec i)))
                    after (get s (+ i r))
                    [left right] (flanking before after)
                    open (if (= c \_) (and left (or (not right) (punct? before))) left)
                    close (if (= c \_) (and right (or (not left) (punct? after))) right)]
                (recur (+ i r) [] (conj (flush-buf out) {:t :delim :ch c :len r :open open :close close})))

              (= c \~)
              (let [r (run-len s i \~)]
                (if (= r 2)
                  (let [before (when (> i 0) (get s (dec i)))
                        after (get s (+ i 2))
                        [left right] (flanking before after)]
                    (recur (+ i 2) [] (conj (flush-buf out) {:t :delim :ch \~ :len 2 :open left :close right})))
                  (recur (+ i r) (into buf (repeat r \~)) out)))

              (and (= c \space)
                   (let [j (loop [j i] (if (and (< j n) (= (get s j) \space)) (recur (inc j)) j))]
                     (and (>= (- j i) 2) (< j n) (= (get s j) \newline))))
              (let [j (loop [j i] (if (and (< j n) (= (get s j) \space)) (recur (inc j)) j))]
                (recur (inc j) [] (conj (flush-buf out) {:t :br})))

              (= c \newline)
              (recur (inc i) [] (conj (flush-buf out) {:t :str :v " "}))

              :else (recur (inc i) (conj buf c) out))))))))

(defn- delim->text [{:keys [ch len]}] (apply str (repeat len ch)))

(defn- find-closer [toks]
  (let [n (count toks)]
    (loop [k 0]
      (cond
        (>= k n) nil
        (let [t (get toks k)] (and (= (:t t) :delim) (:close t) (pos? (:len t)))) k
        :else (recur (inc k))))))

(defn- find-opener [toks ci]
  (let [closer (get toks ci)
        cch (:ch closer)]
    (loop [k (dec ci)]
      (cond
        (< k 0) nil
        (let [o (get toks k)]
          (and (= (:t o) :delim)
               (:open o)
               (pos? (:len o))
               (= (:ch o) cch)
               (or (= cch \~)
                   (not (or (and (:open o) (:close o)) (and (:open closer) (:close closer))))
                   (not (zero? (mod (+ (:len o) (:len closer)) 3)))
                   (and (zero? (mod (:len o) 3)) (zero? (mod (:len closer) 3))))))
        k
        :else (recur (dec k))))))

(defn- process-emphasis [tokens]
  (let [demote (fn [t] (if (= (:t t) :delim) {:t :str :v (delim->text t)} t))]
    (loop [toks (vec tokens)]
      (let [ci (find-closer toks)]
        (if (nil? ci)
          (coalesce-strs (mapv demote toks))
          (let [oi (find-opener toks ci)]
            (if (nil? oi)
              (recur (assoc toks ci (assoc (get toks ci) :close false)))
              (let [opener (get toks oi)
                    closer (get toks ci)
                    cch (:ch closer)
                    use (cond (= cch \~) 2
                              (and (>= (:len opener) 2) (>= (:len closer) 2)) 2
                              :else 1)
                    tag (cond (= cch \~) :del (= use 2) :strong :else :em)
                    inner (coalesce-strs (mapv demote (subvec toks (inc oi) ci)))
                    o-rem (- (:len opener) use)
                    c-rem (- (:len closer) use)
                    mid (cond-> []
                          (pos? o-rem) (conj (assoc opener :len o-rem))
                          true (conj {:t tag :children inner})
                          (pos? c-rem) (conj (assoc closer :len c-rem)))]
                (recur (vec (concat (subvec toks 0 oi) mid (subvec toks (inc ci)))))))))))))

(defn parse-inlines
  "Parses inline CommonMark + GFM markup (code spans, `*em*`/`_em_`, `**strong**`/`__strong__`,
   `~~strike~~`, `[links](url)`, `![images](src)`, safe autolinks, hard breaks) into AST nodes.
   Never interprets raw HTML."
  {:malli/schema [:=> {:examples [[["plain"] [{:t :str :v "plain"}]]
                                  [["**b** `c` ~~s~~ *i*"]
                                   [{:t :strong :children [{:t :str :v "b"}]}
                                    {:t :str :v " "}
                                    {:t :code :v "c"}
                                    {:t :str :v " "}
                                    {:t :del :children [{:t :str :v "s"}]}
                                    {:t :str :v " "}
                                    {:t :em :children [{:t :str :v "i"}]}]]]}
                  [:cat :string] [:vector :map]]
   :cost/ticks 2000000 :cost/bytes 4194304}
  [s]
  (if (empty? s)
    []
    (process-emphasis (tokenize s parse-inlines))))

(def ^:private block-re
  {:atx        #"^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$"
   :thematic   #"^ {0,3}([-*_])[ \t]*(?:\1[ \t]*){2,}$"
   :fence      #"^( {0,3})(`{3,}|~{3,})[ \t]*([^`]*?)[ \t]*$"
   :bullet     #"^( {0,3})([-+*])(?:[ \t]+(.*)|[ \t]*)$"
   :ordered    #"^( {0,3})(\d{1,9})([.)])(?:[ \t]+(.*)|[ \t]*)$"
   :blockquote #"^ {0,3}>[ ]?(.*)$"
   :setext     #"^ {0,3}(=+|-+)[ \t]*$"
   :blank      #"^[ \t]*$"
   :alert      #"(?is)^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\](?:[ \t]*\n|[ \t]+|$)(.*)$"
   :task       #"(?s)^\[([ xX])\](?:[ \t]+(.*)|$)$"})

(defn- blank? [line] (boolean (re-matches (:blank block-re) line)))

(defn- indent-of [line]
  (let [n (count line)]
    (loop [i 0 cols 0]
      (if (>= i n)
        cols
        (let [c (get line i)]
          (cond (= c \space) (recur (inc i) (inc cols))
                (= c \tab)   (recur (inc i) (+ cols 4))
                :else cols))))))

(defn- digits->int [s]
  (reduce (fn [a c] (+ (* 10 a) (- (int c) (int \0)))) 0 s))

(defn- item-match [line]
  (or (when-let [m (re-matches (:bullet block-re) line)]
        {:ordered false :marker (nth m 2) :indent (count (nth m 1)) :content (or (nth m 3) "")})
      (when-let [m (re-matches (:ordered block-re) line)]
        {:ordered true :delim (nth m 3) :start (nth m 2) :indent (count (nth m 1)) :content (or (nth m 4) "")})))

(defn- split-table-row [line]
  (let [t (str/trim line)
        n (count t)
        s0 (if (and (pos? n) (= \| (get t 0))) (subs t 1) t)
        sn (count s0)
        s (if (and (pos? sn) (= \| (get s0 (dec sn)))
                   (not (and (>= sn 2) (= \\ (get s0 (- sn 2))))))
            (subs s0 0 (dec sn))
            s0)
        len (count s)]
    (loop [i 0 buf [] cells [] in-code 0]
      (if (>= i len)
        (conj cells (str/trim (apply str buf)))
        (let [c (get s i)]
          (cond
            (and (zero? in-code) (= c \\) (< (inc i) len) (= (get s (inc i)) \|))
            (recur (+ i 2) (conj buf \|) cells 0)

            (= c \`)
            (let [r (run-len s i \`)]
              (recur (+ i r) (into buf (repeat r \`)) cells
                     (cond (zero? in-code) r (= in-code r) 0 :else in-code)))

            (and (zero? in-code) (= c \|))
            (recur (inc i) [] (conj cells (str/trim (apply str buf))) 0)

            :else
            (recur (inc i) (conj buf c) cells in-code)))))))

(defn- parse-table-align [line]
  (let [t (str/trim line)]
    (when (and (not (str/blank? t))
               (str/includes? t "-")
               (re-matches #"^[|:\-\s]+$" t))
      (let [cells (split-table-row t)]
        (when (and (seq cells) (every? #(re-matches #"^:?-{1,}:?$" %) cells))
          (mapv (fn [c]
                  (let [l? (str/starts-with? c ":")
                        r? (str/ends-with? c ":")]
                    (cond (and l? r?) :center l? :left r? :right :else nil)))
                cells))))))

(defn- table-at? [lines i]
  (when (< (inc i) (count lines))
    (let [h-ln (nth lines i)
          d-ln (nth lines (inc i))]
      (when (and (not (blank? h-ln))
                 (or (str/includes? h-ln "|") (str/includes? d-ln "|")))
        (when-let [align (parse-table-align d-ln)]
          (let [hdr (split-table-row h-ln)]
            (when (= (count hdr) (count align))
              {:header hdr :align align})))))))

(defn- collect-table [lines i {:keys [header align]}]
  (let [ncols (count align)
        nlines (count lines)]
    (loop [j (+ i 2) rows []]
      (let [ln (when (< j nlines) (nth lines j))]
        (if (or (nil? ln) (blank? ln) (not (str/includes? ln "|"))
                (re-matches (:atx block-re) ln)
                (re-matches (:fence block-re) ln)
                (re-matches (:blockquote block-re) ln)
                (re-matches (:thematic block-re) ln)
                (item-match ln))
          [{:t :table :align align :header header :rows rows} j]
          (let [raw (split-table-row ln)
                padded (if (< (count raw) ncols)
                         (into raw (repeat (- ncols (count raw)) ""))
                         (subvec raw 0 ncols))]
            (recur (inc j) (conj rows padded))))))))

(defn- collect-fenced [lines i match]
  (let [[_ pad fence info] match
        fence-ch (first fence)
        re-close (re-pattern (str "^ {0,3}[" fence-ch "]{" (count fence) ",}[ \\t]*$"))
        plen (count pad)
        nlines (count lines)]
    (loop [j (inc i) acc []]
      (if (or (>= j nlines) (re-matches re-close (nth lines j)))
        [{:t :code-block :info (str/trim (str info)) :content (str/join "\n" acc)}
         (if (< j nlines) (inc j) j)]
        (let [ln (nth lines j)
              strip-n (min plen (count (take-while #(= % \space) ln)))]
          (recur (inc j) (conj acc (subs ln strip-n))))))))

(defn- strip-cols [ln cols]
  (let [n (count ln)]
    (loop [i 0 c 0]
      (cond
        (or (>= i n) (>= c cols)) (subs ln i)
        (= (get ln i) \space) (recur (inc i) (inc c))
        (= (get ln i) \tab) (recur (inc i) (+ c 4))
        :else (subs ln i)))))

(defn- collect-indented [lines i]
  (let [nlines (count lines)]
    (loop [j i acc []]
      (if (and (< j nlines)
               (let [ln (nth lines j)] (or (blank? ln) (>= (indent-of ln) 4))))
        (recur (inc j) (conj acc (nth lines j)))
        (let [trimmed (->> (reverse acc) (drop-while blank?) reverse)
              content (str/join "\n" (map #(if (blank? %) "" (strip-cols % 4)) trimmed))]
          [{:t :code-block :info "" :content content} j])))))

(defn- collect-blockquote [lines i parse-blocks-fn]
  (let [nlines (count lines)]
    (loop [j i acc []]
      (if (and (< j nlines) (re-matches (:blockquote block-re) (nth lines j)))
        (recur (inc j) (conj acc (second (re-matches (:blockquote block-re) (nth lines j)))))
        (let [children (parse-blocks-fn acc)
              fst (first children)
              am (when (= :para (:t fst)) (re-matches (:alert block-re) (:text fst)))]
          (if am
            (let [kind (keyword (str/lower-case (second am)))
                  rem-txt (str/trim (or (nth am 2) ""))
                  new-kids (if (str/blank? rem-txt)
                             (vec (rest children))
                             (into [{:t :para :text rem-txt}] (rest children)))]
              [{:t :alert :kind kind :children new-kids} j])
            [{:t :blockquote :children children} j]))))))

(defn- marker-str [m]
  (if (:ordered m) (str (:start m) (:delim m)) (str (:marker m))))

(defn- collect-list [lines i first-item parse-blocks-fn]
  (let [ordered (:ordered first-item)
        nlines (count lines)
        same? (fn [m] (and m (= (:ordered m) ordered) (= (:indent m) (:indent first-item))
                           (if ordered (= (:delim m) (:delim first-item))
                               (= (:marker m) (:marker first-item)))))]
    (loop [j i items [] loose false]
      (let [j2 (loop [k j] (if (and (< k nlines) (blank? (nth lines k))) (recur (inc k)) k))
            between-blank (> j2 j)
            line (when (< j2 nlines) (nth lines j2))
            m (when line (item-match line))]
        (if (and m (same? m))
          (let [cont-indent (+ (:indent m) (count (marker-str m)) 1)
                [acc next-j item-blank]
                (loop [k (inc j2) acc [(:content m)] saw-blank false had-blank false]
                  (let [ln (when (< k nlines) (nth lines k))
                        mm (when ln (item-match ln))]
                    (cond
                      (nil? ln) [acc k had-blank]
                      (blank? ln) (recur (inc k) (conj acc "") true had-blank)
                      (and mm (<= (:indent mm) (:indent m)))
                      [acc k (or had-blank (and saw-blank (same? mm)))]
                      (>= (indent-of ln) cont-indent)
                      (recur (inc k) (conj acc (strip-cols ln cont-indent)) false (or had-blank saw-blank))
                      (not saw-blank)
                      (recur (inc k) (conj acc (str/triml ln)) false had-blank)
                      :else [acc k had-blank])))
                raw-kids (parse-blocks-fn acc)
                fst (first raw-kids)
                tm (when (= :para (:t fst)) (re-matches (:task block-re) (:text fst)))
                item (if tm
                       {:t :item
                        :checked (not= " " (second tm))
                        :children (into [{:t :para :text (or (nth tm 2) "")}] (rest raw-kids))}
                       {:t :item :children raw-kids})]
            (recur next-j (conj items item) (or loose between-blank item-blank)))
          [{:t :list :ordered ordered
            :start (when ordered (digits->int (:start first-item)))
            :tight (not loose)
            :has-tasks? (boolean (some #(some? (:checked %)) items))
            :items items}
           j])))))

(defn- collect-paragraph [lines i]
  (let [nlines (count lines)]
    (loop [j i acc []]
      (let [ln (when (< j nlines) (nth lines j))]
        (cond
          (and ln (seq acc) (not (table-at? lines j)) (re-matches (:setext block-re) ln))
          [{:t :heading :level (if (= \= (first (str/trim ln))) 1 2)
            :text (str/join "\n" acc)} (inc j)]

          (or (nil? ln) (blank? ln)
              (and (seq acc) (table-at? lines j))
              (re-matches (:thematic block-re) ln)
              (re-matches (:atx block-re) ln)
              (re-matches (:fence block-re) ln)
              (re-matches (:blockquote block-re) ln)
              (item-match ln))
          [{:t :para :text (str/join "\n" acc)} j]

          :else (recur (inc j) (conj acc (str/triml ln))))))))

(defn parse-blocks
  "Parses a vector of CommonMark + GFM lines into a vector of block AST maps
   (headings, paragraphs, pipe tables, blockquotes, GitHub alerts, ordered/unordered/task lists,
   thematic breaks, fenced & indented code blocks)."
  {:malli/schema [:=> {:examples [[[[ "# Hi" "" "Hello"]]
                                   [{:t :heading :level 1 :text "Hi"}
                                    {:t :para :text "Hello"}]]]}
                  [:cat [:vector :string]] [:vector :map]]
   :cost/ticks 5000000 :cost/bytes 8388608}
  [lines]
  (let [nlines (count lines)]
    (loop [i 0 out []]
      (if (>= i nlines)
        out
        (let [line (nth lines i)]
          (cond
            (blank? line) (recur (inc i) out)

            (re-matches (:thematic block-re) line)
            (recur (inc i) (conj out {:t :hr}))

            (re-matches (:atx block-re) line)
            (let [m (re-matches (:atx block-re) line)]
              (recur (inc i) (conj out {:t :heading :level (count (nth m 1)) :text (or (nth m 2) "")})))

            (re-matches (:fence block-re) line)
            (let [[node ni] (collect-fenced lines i (re-matches (:fence block-re) line))]
              (recur ni (conj out node)))

            (table-at? lines i)
            (let [[node ni] (collect-table lines i (table-at? lines i))]
              (recur ni (conj out node)))

            (re-matches (:blockquote block-re) line)
            (let [[node ni] (collect-blockquote lines i parse-blocks)]
              (recur ni (conj out node)))

            (item-match line)
            (let [[node ni] (collect-list lines i (item-match line) parse-blocks)]
              (recur ni (conj out node)))

            (>= (indent-of line) 4)
            (let [[node ni] (collect-indented lines i)]
              (recur ni (conj out node)))

            :else
            (let [[node ni] (collect-paragraph lines i)]
              (recur ni (conj out node)))))))))

(defn parse-document
  "Normalizes line endings on Markdown string `s` and parses it into a vector of block AST nodes."
  {:malli/schema [:=> {:examples [[["# Title\n\nBody"]
                                   [{:t :heading :level 1 :text "Title"}
                                    {:t :para :text "Body"}]]]}
                  [:cat [:maybe :string]] [:vector :map]]
   :cost/ticks 5000000 :cost/bytes 8388608}
  [s]
  (if (or (nil? s) (str/blank? s))
    []
    (let [normalized (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n"))
          lines (vec (str/split normalized #"\n" -1))]
      (parse-blocks lines))))

(defn classify-url
  "Classifies a Markdown link/image URL into `:internal`, `:external`, `:mailto`, `:fragment`, or `:unsafe`
   (stripping control characters and neutralizing `javascript:`, `data:`, `vbscript:`, and `//...`)."
  {:malli/schema [:=> {:examples [[["/var/seon.web/web-proc"] {:kind :internal :href "/var/seon.web/web-proc"}]
                                  [["https://example.com"] {:kind :external :href "https://example.com"}]
                                  [["javascript:alert(1)"] {:kind :unsafe :href ""}]
                                  [["data:text/html,<h1>x</h1>"] {:kind :unsafe :href ""}]]}
                  [:cat [:maybe :string]] [:map [:kind :keyword] [:href :string]]]
   :cost/ticks 200000 :cost/bytes 131072}
  [raw-url]
  (let [cleaned (-> (str (or raw-url ""))
                    (str/replace #"[\u0000-\u001f\u007f]+" "")
                    str/trim)
        lo (str/lower-case cleaned)]
    (cond
      (str/blank? cleaned) {:kind :unsafe :href ""}
      (and (str/starts-with? cleaned "/") (not (str/starts-with? cleaned "//"))) {:kind :internal :href cleaned}
      (str/starts-with? cleaned "#") {:kind :fragment :href cleaned}
      (or (str/starts-with? lo "https://") (str/starts-with? lo "http://")) {:kind :external :href cleaned}
      (str/starts-with? lo "mailto:") {:kind :mailto :href cleaned}
      :else {:kind :unsafe :href ""})))

(defn safe-read-edn
  "Safely parses pure EDN string `s` with `*read-eval* false`, returning `{:ok? true :val v}` or `{:ok? false :error msg}`."
  {:malli/schema [:=> {:examples [[["{:a 1}"] {:ok? true :val {:a 1}}]]}
                  [:cat [:maybe :string]] :map]
   :cost/ticks 500000 :cost/bytes 524288}
  [s]
  (try
    {:ok? true :val (binding [*read-eval* false] (edn/read-string (str (or s ""))))}
    (catch Throwable ex
      {:ok? false :error (or (.getMessage ex) (str ex))})))

(def ^:private safe-hiccup-tags
  #{:div :span :p :a :h1 :h2 :h3 :h4 :h5 :h6 :ul :ol :li :dl :dt :dd
    :table :thead :tbody :tfoot :tr :th :td :caption :blockquote :pre :code
    :strong :b :em :i :del :s :u :small :sub :sup :mark :br :hr
    :details :summary :figure :figcaption :section :article :header :footer
    :nav :aside :meter :progress :time :kbd :abbr :img
    :svg :g :path :circle :ellipse :rect :line :polyline :polygon :text :tspan
    :defs :linearGradient :radialGradient :stop :clipPath :title :desc :marker})

(def ^:private safe-hiccup-attrs
  #{:class :id :title :role :colspan :rowspan :open :width :height :alt
    :value :min :max :low :high :optimum :datetime :href :src :target :rel :style
    :aria-label :aria-valuenow :aria-valuemin :aria-valuemax :aria-hidden
    :viewBox :viewbox :xmlns :fill :fill-opacity :fill-rule :stroke :stroke-width
    :stroke-linecap :stroke-linejoin :stroke-dasharray :stroke-opacity :opacity
    :x :y :x1 :y1 :x2 :y2 :cx :cy :r :rx :ry :dx :dy :d :points :transform
    :text-anchor :dominant-baseline :font-size :font-family :font-weight
    :letter-spacing :offset :stop-color :stop-opacity :gradientUnits :preserveAspectRatio})

(defn- safe-style-str? [s]
  (let [lo (str/lower-case (str (or s "")))]
    (not (or (str/includes? lo "javascript:") (str/includes? lo "vbscript:")
             (str/includes? lo "expression(") (str/includes? lo "url(")
             (str/includes? lo "@import")))))

(defn sanitize-hiccup
  "Sanitizes an untrusted pure-EDN Hiccup tree against a strict HTML/SVG tag and attribute whitelist:
   drops `<script>`, `<iframe>`, `on*`/`data-on*` event attrs, `javascript:`/`data:` URLs, and raw HTML maps."
  {:malli/schema [:=> {:examples [[[[ :div {:class "box" :onclick "evil()"} [:script "x"] "ok"]]
                                   [:div {:class "box"} "ok"]]
                                  [[[:a {:href "javascript:alert(1)"} "click"]]
                                   [:a "click"]]]}
                  [:cat :any] :any]
   :cost/ticks 2000000 :cost/bytes 4194304}
  [node]
  (letfn [(clean-attrs [m]
            (reduce-kv
             (fn [acc k v]
               (if (and (simple-keyword? k) (contains? safe-hiccup-attrs k) (some? v))
                 (case k
                   :href (let [{:keys [kind href]} (classify-url (str v))]
                           (case kind
                             :external (assoc acc :href href :target "_blank" :rel "noopener noreferrer")
                             (:internal :fragment :mailto) (assoc acc :href href)
                             acc))
                   :src (let [{:keys [kind href]} (classify-url (str v))]
                          (if (#{:external :internal} kind) (assoc acc :src href) acc))
                   :style (cond
                            (string? v) (if (safe-style-str? v) (assoc acc :style v) acc)
                            (map? v) (let [sm (into {} (keep (fn [[sk sv]]
                                                               (when (and (or (simple-keyword? sk) (string? sk))
                                                                          (or (string? sv) (number? sv) (keyword? sv))
                                                                          (safe-style-str? (str sv)))
                                                                 [sk sv]))) v)]
                                       (if (seq sm) (assoc acc :style sm) acc))
                            :else acc)
                   (if (or (string? v) (number? v) (boolean? v) (keyword? v)) (assoc acc k v) acc))
                 acc))
             {} m))
          (walk [n depth]
            (cond
              (or (nil? n) (false? n) (> depth 24)) nil
              (or (string? n) (number? n) (boolean? n)) n
              (or (keyword? n) (symbol? n)) (str n)
              (map? n) nil
              (seq? n) (let [xs (vec (keep #(walk % (inc depth)) n))] (when (seq xs) xs))
              (vector? n)
              (let [hd (first n)]
                (cond
                  (and (simple-keyword? hd) (contains? safe-hiccup-tags hd))
                  (let [has-attrs? (map? (second n))
                        attrs (when has-attrs? (clean-attrs (second n)))
                        kids (keep #(walk % (inc depth)) (if has-attrs? (drop 2 n) (rest n)))]
                    (into (if (seq attrs) [hd attrs] [hd]) kids))
                  (not (keyword? hd))
                  (let [xs (vec (keep #(walk % (inc depth)) n))] (when (seq xs) xs))
                  :else nil))
              :else nil))]
    (walk node 0)))
