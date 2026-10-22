(ns seon.ref
  "References in Markdown text (a plan, a doc page, a docstring), found by one parse and read
  by one rule (v1's seon.ref, TASK-CHAIN §3a; docs/wiki.md#references): a code span is a
  candidate name or path, a link a path, a prose word holding a / a name when it names
  something, and the delimiter picks the depth shown. The scanner reads the CommonMark subset
  the rule needs (ATX headings, fenced and indented code, code spans, inline links,
  paragraph boundaries) in one pass over the text."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.db :as pdb] [seon.reader :as reader]
            [clojure.string :as str] [clojure.java.io :as io] [datahike.api :as d] [seon.config :as cfg]))

(def roots
  "The checkout's top-level entries (docs, src, AGENTS.md, ...), read at first use: the bases
  a path is cited from. A delay, so compiling this namespace lists nothing: Jolt's compile
  cache keys a namespace by what its compile read, and the listing (it differs between
  checkouts: runs/, .jolt/) would key it, and its dependents, per checkout."
  (delay (into #{} (remove #(str/starts-with? % ".")) (.list (io/file (cfg/checkout))))))

;; ---- the scan: blocks, then the inlines of the blocks that hold prose ---------------------

(defn- punct?
  "Is c ASCII punctuation (what a backslash escapes)?"
  {:malli/schema [:=> [:cat char?] :boolean]}
  [c] (boolean (str/index-of "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" (str c))))

(defn- unescape
  "s with each backslash before ASCII punctuation removed."
  {:malli/schema [:=> [:cat :string] :string]}
  [^String s]
  (if-not (str/index-of s "\\")
    s
    (let [sb (StringBuilder.) n (count s)]
      (loop [i 0]
        (if (< i n)
          (let [c (.charAt s i)]
            (if (and (= \\ c) (< (inc i) n) (punct? (.charAt s (inc i))))
              (do (.append sb (.charAt s (inc i))) (recur (+ i 2)))
              (do (.append sb c) (recur (inc i)))))
          (.toString sb))))))

(defn scan
  "Markdown text read once: its blocks, in order, and the inlines of every block that holds
  prose (code spans with their backtick width, link destinations, prose words holding a /),
  in document order; fenced and indented code hold none. One pass: index-of finds each line,
  its first non-space character picks its block, and each prose character is read once,
  jumping over a code span or a link. A heading block carries its text, and the block after
  a heading its own text (the section's lead or opening).

  The CommonMark 0.31.2 subset the reference rule needs: ATX headings (4.2), fenced (4.5) and
  indented (4.4) code, paragraphs ended by a blank line, a heading or a fence (and, for a
  paragraph, by a list item, a quote or a thematic break), code spans (6.1), inline links
  (6.3). Setext headings, reference links and HTML blocks are out of it. The helpers are local:
  they work on offsets into this one text."
  {:malli/schema [:=> [:cat :string] :seon.ref/scan]}
  [^String text]
  (letfn [(run-end [i end c]
            ;; the offset after the run of char c starting at i, before end
            (loop [j i] (if (and (< j end) (= c (.charAt text j))) (recur (inc j)) j)))
          (closer [from end n]
            ;; the offset of the backtick run of exactly n that closes a code span opened before from
            (loop [i from]
              (let [j (str/index-of text "`" i)]
                (when (and j (< j end))
                  (let [k (run-end j end \`)] (if (= n (- k j)) j (recur k)))))))
          (code-content [from to]
            ;; line endings become spaces; one space off each side when both are and it is not all spaces
            (let [^String s (str/replace (subs text from to) "\n" " ") n (count s)]
              (if (and (> n 1) (= \space (.charAt s 0)) (= \space (.charAt s (dec n))) (not (str/blank? s)))
                (subs s 1 (dec n))
                s)))
          (newlines [from to]
            (loop [i from k 0] (if (< i to) (recur (inc i) (if (= \newline (.charAt text i)) (inc k) k)) k)))
          (skip-space [j end]
            ;; past spaces and tabs and at most one line ending
            (loop [j j nl 0]
              (if (< j end)
                (let [c (.charAt text j)]
                  (cond (or (= \space c) (= \tab c)) (recur (inc j) nl)
                        (and (= \newline c) (zero? nl)) (recur (inc j) 1)
                        :else j))
                j)))
          (title-end [i end]
            ;; the offset after a link title opening at i: "t", 't' or (t)
            (let [close (case (.charAt text i) \" \" \' \' \( \) nil)]
              (when close
                (loop [j (inc i)]
                  (when (< j end)
                    (let [c (.charAt text j)]
                      (cond (= \\ c) (recur (+ j 2))
                            (= close c) (inc j)
                            (and (= \( close) (= \( c)) nil
                            :else (recur (inc j)))))))))
          (destination [from end]
            ;; [dest-from dest-to after] of a link destination at from: <...>, or balanced parentheses up to a space
            (if (and (< from end) (= \< (.charAt text from)))
              (loop [j (inc from)]
                (when (< j end)
                  (case (.charAt text j)
                    \\ (recur (+ j 2))
                    \> [(inc from) j (inc j)]
                    (\< \newline) nil
                    (recur (inc j)))))
              (loop [j from depth 0]
                (if (>= j end)
                  (when (zero? depth) [from j j])
                  (let [c (.charAt text j)]
                    (cond (= \\ c) (recur (+ j 2) depth)
                          (= \( c) (recur (inc j) (inc depth))
                          (= \) c) (if (zero? depth) [from j j] (recur (inc j) (dec depth)))
                          (or (= \space c) (= \tab c) (= \newline c) (< (int c) 32)) (when (zero? depth) [from j j])
                          :else (recur (inc j) depth)))))))
          (link-at [i end]
            ;; [text-end dest-from dest-to after] of the inline link whose text opens at i, or nil;
            ;; a code span binds tighter than the brackets
            (let [close (loop [j (inc i) depth 1]
                          (when (< j end)
                            (case (.charAt text j)
                              \\ (recur (+ j 2) depth)
                              \` (let [k (run-end j end \`) c (closer k end (- k j))] (recur (if c (+ c (- k j)) k) depth))
                              \[ (recur (inc j) (inc depth))
                              \] (if (= 1 depth) j (recur (inc j) (dec depth)))
                              (recur (inc j) depth))))]
              (when (and close (< (inc close) end) (= \( (.charAt text (inc close))))
                (when-let [[df dt after] (destination (skip-space (+ close 2) end) end)]
                  (let [t (skip-space after end)
                        t (if (and (< t end) (> t after) (#{\" \' \(} (.charAt text t))) (some-> (title-end t end) (skip-space end)) t)]
                    (when (and t (< t end) (= \) (.charAt text t)))
                      [close df dt (inc t)]))))))
          (word [out slash? from to line]
            ;; out with the prose word between from and to when it holds a /
            (cond-> out (and slash? (< from to)) (conj! {:seon.ref/word (subs text from to) :seon.ref/line line})))
          (inlines [from to line]
            ;; each code span, link destination (then its text's inlines) and prose word holding a /, in order
            (loop [i from line line wstart from slash? false out (transient [])]
              (if (>= i to)
                (persistent! (word out slash? wstart to line))
                (let [c (.charAt text i)]
                  (case c
                    \newline (recur (inc i) (inc line) (inc i) false (word out slash? wstart i line))
                    (\space \tab \return) (recur (inc i) line (inc i) false (word out slash? wstart i line))
                    \/ (recur (inc i) line wstart true out)
                    \\ (recur (if (and (< (inc i) to) (punct? (.charAt text (inc i)))) (+ i 2) (inc i)) line wstart slash? out)
                    \` (let [k (run-end i to \`) n (- k i) e (closer k to n)]
                         (if e
                           (recur (+ e n) (+ line (newlines k e)) (+ e n) false
                                  (conj! (word out slash? wstart i line) {:seon.ref/code (code-content k e) :seon.ref/width n :seon.ref/line line}))
                           (recur k line wstart slash? out)))
                    \[ (if-let [[close df dt after] (link-at i to)]
                         (let [image? (and (> i from) (= \! (.charAt text (dec i))))
                               out (cond-> (word out slash? wstart i line)
                                     (not image?) (conj! {:seon.ref/link (unescape (subs text df dt)) :seon.ref/line (+ line (newlines i df))}))]
                           (recur after (+ line (newlines i after)) after false (reduce conj! out (inlines (inc i) close line))))
                         (recur (inc i) line wstart slash? out))
                    (recur (inc i) line wstart slash? out))))))
          (heading-text [from to]
            ;; code spans as their content, links as their text, emphasis stars dropped, escapes resolved
            (let [sb (StringBuilder.)]
              (loop [i from]
                (if (>= i to)
                  (str/trim (.toString sb))
                  (let [c (.charAt text i)]
                    (cond
                      (and (= \\ c) (< (inc i) to) (punct? (.charAt text (inc i)))) (do (.append sb (.charAt text (inc i))) (recur (+ i 2)))
                      (= \` c) (let [k (run-end i to \`) e (closer k to (- k i))]
                                 (if e (do (.append sb (code-content k e)) (recur (+ e (- k i))))
                                     (do (.append sb (subs text i k)) (recur k))))
                      (= \[ c) (if-let [[close _ _ after] (link-at i to)]
                                 (do (.append sb (heading-text (inc i) close)) (recur after))
                                 (do (.append sb c) (recur (inc i))))
                      (= \* c) (recur (inc i))
                      :else (do (.append sb c) (recur (inc i)))))))))
          (heading-at [p e]
            ;; [level text-from text-to]: 1-6 #, then a space, a tab or the end; a closing run of # after a space
            (let [k (run-end p e \#) level (- k p)]
              (when (and (<= 1 level 6) (or (= k e) (= \space (.charAt text k)) (= \tab (.charAt text k))))
                (let [blank? (fn [j] (let [c (.charAt text j)] (or (= \space c) (= \tab c) (= \return c))))
                      tf (loop [j k] (if (and (< j e) (blank? j)) (recur (inc j)) j))
                      te (loop [j e] (if (and (> j tf) (blank? (dec j))) (recur (dec j)) j))
                      hashes (loop [j te] (if (and (> j tf) (= \# (.charAt text (dec j)))) (recur (dec j)) j))
                      te (cond (= hashes tf) tf
                               (and (< hashes te) (blank? (dec hashes))) (loop [j hashes] (if (and (> j tf) (blank? (dec j))) (recur (dec j)) j))
                               :else te)]
                  [level tf te]))))
          (fence-at [p e]
            ;; [char length]: three or more ` or ~; a backtick fence's info string holds no backtick
            (let [c (.charAt text p) k (run-end p e c)]
              (when (and (or (= \` c) (= \~ c)) (<= 3 (- k p)) (or (= \~ c) (not (some-> (str/index-of text "`" k) (< e)))))
                [c (- k p)])))
          (fence-closes? [p e c n]
            ;; a run of c at least n long, then only spaces
            (let [k (run-end p e c)]
              (and (<= n (- k p)) (loop [j k] (or (= j e) (and (#{\space \tab \return} (.charAt text j)) (recur (inc j))))))))
          (start-kind [p e]
            ;; a thematic break, a list item, a block quote, a table row, else a paragraph
            (let [c (.charAt text p)
                  after? (fn [j] (or (= j e) (= \space (.charAt text j)) (= \tab (.charAt text j))))
                  digits (loop [j p] (if (and (< j e) (< (- j p) 9) (Character/isDigit (.charAt text j))) (recur (inc j)) j))]
              (cond
                (and (or (= \- c) (= \* c) (= \_ c))
                     (loop [j p k 0]
                       (if (< j e)
                         (let [d (.charAt text j)]
                           (cond (= c d) (recur (inc j) (inc k))
                                 (or (= \space d) (= \tab d) (= \return d)) (recur (inc j) k)
                                 :else false))
                         (<= 3 k)))) :break
                (and (or (= \- c) (= \* c) (= \+ c)) (after? (inc p))) :list
                (and (> digits p) (< digits e) (or (= \. (.charAt text digits)) (= \) (.charAt text digits))) (after? (inc digits))) :list
                (= \> c) :quote
                (= \| c) :table
                :else :para)))
          (emit [out kind from to line end-line]
            ;; one block; the block after a heading carries its text (the section's lead or opening)
            (let [b {:seon.ref/kind kind :seon.ref/from from :seon.ref/to to :seon.ref/line line :seon.ref/end-line (max line end-line)}
                  prev (when (pos? (count out)) (nth out (dec (count out))))]
              (conj! out (cond-> b (= :heading (:seon.ref/kind prev)) (assoc :seon.ref/text (str/trimr (subs text from to)))))))]
    (let [n (count text)
          ;; one pass over the lines. The open block: okind (nil: none) from ofrom on line oline;
          ;; prev-e, the previous line's end; fch and flen, an open fence's char and length.
          [bs line-count]
          (loop [pos 0 line 1 okind nil ofrom 0 oline 1 prev-e 0 fch nil flen 0 list? false out (transient [])]
            (if (>= pos n)
              [(persistent! (cond-> out okind (emit okind ofrom prev-e oline (dec line)))) (dec line)]
              (let [e (or (str/index-of text "\n" pos) n)
                    nx (inc e)
                    p (loop [j pos] (if (and (< j e) (let [c (.charAt text j)] (or (= \space c) (= \tab c)))) (recur (inc j)) j))
                    indent (loop [j pos w 0] (if (< j p) (recur (inc j) (if (= \tab (.charAt text j)) (+ w (- 4 (mod w 4))) (inc w))) w))
                    c (if (< p e) (.charAt text p) \newline)
                    blank? (or (= p e) (and (= \return c) (= (inc p) e)))
                    ;; inside a list, a fence or heading may be indented past three spaces
                    nested? (or (<= indent 3) list?)
                    h (when (and nested? (not fch) (= \# c)) (heading-at p e))
                    f (when (and nested? (not fch) (or (= \` c) (= \~ c))) (fence-at p e))]
                (cond
                  fch
                  (if (and (not blank?) nested? (= fch c) (fence-closes? p e fch flen))
                    (recur nx (inc line) nil 0 1 e nil 0 list? (emit out :fence ofrom e oline line))
                    (recur nx (inc line) okind ofrom oline e fch flen list? out))

                  blank? (recur nx (inc line) nil 0 1 e nil 0 list? (cond-> out okind (emit okind ofrom prev-e oline (dec line))))

                  h
                  (let [[level tf te] h]
                    (recur nx (inc line) nil 0 1 e nil 0 list?
                           (conj! (cond-> out okind (emit okind ofrom prev-e oline (dec line))) {:seon.ref/kind :heading :seon.ref/from pos :seon.ref/to e :seon.ref/line line
                                                :seon.ref/end-line line :seon.ref/heading-level level
                                                :seon.ref/heading (heading-text tf te) :seon.ref/text-from tf :seon.ref/text-to te})))

                  f (recur nx (inc line) :fence pos line e (nth f 0) (nth f 1) list? (cond-> out okind (emit okind ofrom prev-e oline (dec line))))

                  ;; a list, a quote or a table runs to a blank line; a paragraph's next line continues it
                  ;; unless it starts a list item, a quote or a break (an indented line never interrupts it)
                  (or (#{:list :quote :table} okind)
                      (and (= :para okind)
                           (or (> indent 3)
                               (case c (\- \* \+ \> \_ \0 \1 \2 \3 \4 \5 \6 \7 \8 \9) (= :para (start-kind p e)) true))))
                  (recur nx (inc line) okind ofrom oline e nil 0 list? out)

                  ;; an indented line is code, or a list item's own content inside a list
                  (> indent 3)
                  (let [kind (if list? :list :code)]
                    (if (= kind okind)
                      (recur nx (inc line) okind ofrom oline e nil 0 list? out)
                      (recur nx (inc line) kind pos line e nil 0 list? (cond-> out okind (emit okind ofrom prev-e oline (dec line))))))

                  :else
                  (let [k (start-kind p e)
                        kind (if (and list? (pos? indent) (= :para k)) :list k)]
                    (recur nx (inc line) kind pos line e nil 0 (= :list kind) (cond-> out okind (emit okind ofrom prev-e oline (dec line)))))))))]
      {:seon.ref/blocks bs
       :seon.ref/line-count line-count
       :seon.ref/spans (into [] (comp (remove (comp #{:fence :code :break} :seon.ref/kind))
                                      (mapcat (fn [{:seon.ref/keys [kind from to line text-from text-to]}]
                                                (if (= :heading kind) (inlines text-from text-to line) (inlines from to line)))))
                             bs)})))

(defn- slug
  "A heading's anchor before deduplication, as GitHub writes it: lower case, each space a -,
  every character but a letter, digit, - or _ dropped."
  {:malli/schema [:=> [:cat :string] :string]}
  [heading]
  (let [^String s (str/lower-case heading) n (count s) sb (StringBuilder.)]
    (loop [i 0]
      (if (< i n)
        (let [c (.charAt s i)]
          (cond (or (Character/isLetterOrDigit c) (= \- c) (= \_ c)) (.append sb c)
                (= \space c) (.append sb \-))
          (recur (inc i)))
        (.toString sb)))))

;; ---- candidates: what each inline names, at the depth its delimiter asks for -------------

(defn- line-citation
  "The path of a `path:N` or `path:A-B` citation, or nil."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [span]
  (when-let [colon (str/last-index-of span ":")]
    (let [suffix (subs span (inc colon))
          dash (str/index-of suffix "-")
          [from to] (if dash [(subs suffix 0 dash) (subs suffix (inc dash))] [suffix])]
      (when (and (parse-long from) (or (nil? to) (parse-long to)))
        (subs span 0 colon)))))

(defn- based
  "A path with an optional #anchor as a candidate."
  {:malli/schema [:=> [:cat :string] [:map [:seon.ref/path :string] [:seon.ref/anchor {:optional true} :string]]]}
  [s]
  (let [h (str/index-of s "#")]
    (cond-> {:seon.ref/path (if h (subs s 0 h) s)}
      (and h (< (inc h) (count s))) (assoc :seon.ref/anchor (subs s (inc h))))))

(defn- namespace-name?
  "Is s one dotted simple symbol (letters, digits, - and _ in dot-separated segments, the first
  a letter): what a namespace is called?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String s]
  (let [n (count s)]
    (boolean (and (str/index-of s ".") (Character/isLetter (.charAt s 0))
                  (loop [i 0 prev \.]
                    (if (= i n)
                      (not= \. prev)
                      (let [c (.charAt s i)]
                        (and (or (Character/isLetterOrDigit c) (= \- c) (= \_ c) (and (= \. c) (not= \. prev)))
                             (recur (inc i) c)))))))))

(defn- span-candidate
  "A code span or prose word read as a based path (its first segment one of the checkout's
  top-level entries), a line citation, a name (exactly one qualified symbol or keyword, as
  written, not ending in *), a namespace (one dotted simple symbol, resolve-only: a reference
  only when it names a namespace, v1's rule), or nil: prose or code."
  {:malli/schema [:=> [:cat :string] [:maybe :map]]}
  [span]
  (let [cut (reduce (fn [m s] (if-let [i (str/index-of span s)] (min m i) m)) (count span) ["/" "#" ":"])]
    (if (contains? @roots (subs span 0 cut))
      (when-not (or (str/index-of span " ") (str/index-of span "\t"))
        (if-let [cited (line-citation span)]
          {:seon.ref/path cited :seon.ref/problem-kind :line-reference}
          (based span)))
      ;; a namespace as written is a dotted simple symbol; a qualified name holds a / and no
      ;; space: only such a span is read
      (if-not (str/index-of span "/")
        (when (namespace-name? span) {:seon.ref/name (symbol span) :seon.ref/resolve-only true})
        (when (and (not (str/index-of span " ")) (not (str/index-of span "\t")) (not (str/ends-with? span "*")))
          (let [[x & more] (reader/read-text span 'user)]
            (when (and (nil? more) (or (qualified-symbol? x) (qualified-keyword? x)) (= span (str x)))
              {:seon.ref/name x})))))))

(defn- link-candidate
  "A link destination as a candidate: one with a scheme (https:, mailto:) is no reference; an
  anchor alone names the document's own path (with no path, :anchor-missing); a relative one
  resolves against the document's directory."
  {:malli/schema [:=> [:cat [:maybe :string] :string] [:maybe :map]]}
  [doc-path dest]
  (let [colon (str/index-of dest ":")
        scheme? (and colon (pos? colon) (not-any? #(some-> (str/index-of dest %) (< colon)) ["/" "?" "#"]))]
    (cond
      (or (= "" dest) scheme?) nil
      (str/starts-with? dest "#")
      (if doc-path
        (cond-> {:seon.ref/path doc-path} (< 1 (count dest)) (assoc :seon.ref/anchor (subs dest 1)))
        (cond-> {:seon.ref/problem-kind :anchor-missing} (< 1 (count dest)) (assoc :seon.ref/anchor (subs dest 1))))
      :else
      (let [{p :seon.ref/path :as c} (based (subs dest 0 (or (str/index-of dest "?") (count dest))))
            dir (when doc-path (some->> (str/last-index-of doc-path "/") (subs doc-path 0)))
            joined (if (and dir (not (str/starts-with? p "/"))) (str dir "/" p) p)
            parts (reduce (fn [acc s] (case s ("" ".") acc ".." (if (seq acc) (pop acc) acc) (conj acc s)))
                          [] (str/split joined #"/"))]
        (when (seq parts)
          (assoc c :seon.ref/path (str (when (str/starts-with? joined "/") "/") (str/join "/" parts))))))))

(defn- trim-word
  "A prose word without the punctuation around it, or nil when nothing is left."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [^String word]
  (let [n (count word)
        from (loop [i 0] (if (and (< i n) (str/index-of "([{\"'`*_<" (str (.charAt word i)))) (recur (inc i)) i))
        to (loop [j n] (if (and (> j from) (str/index-of ".,;:!?)]}\"'`*_>" (str (.charAt word (dec j))))) (recur (dec j)) j))]
    (when (< from to) (subs word from to))))

(defn- candidate
  "One inline as a citation at the depth its delimiter asks for (a prose word its summary,
  one backtick or a link its doc, three or more backticks its code; two backticks wrap a
  literal), or nil. A prose word is resolve-only."
  {:malli/schema [:=> [:cat [:maybe :string] :seon.ref/span] [:maybe :seon.ref/citation]]}
  [doc-path {:seon.ref/keys [code width link word line]}]
  (let [[c depth] (cond link [(link-candidate doc-path link) :doc]
                        word [(some-> (trim-word word) span-candidate (assoc :seon.ref/resolve-only true)) :summary]
                        (= 1 width) [(span-candidate code) :doc]
                        (<= 3 width) [(span-candidate code) :code])]
    (some-> c (assoc :seon.ref/depth depth :seon.ref/line line))))

(def ^:private strength {:summary 0 :doc 1 :code 2})

(defn cited
  "Citations once each, in first-mention order, each at the strongest depth any mention asks
  for and on its first mention's line; resolve-only only when every mention is."
  {:malli/schema [:=> [:cat [:vector :seon.ref/citation]] [:vector :seon.ref/citation]]}
  [citations]
  (let [id (juxt :seon.ref/name :seon.ref/path :seon.ref/anchor :seon.ref/problem-kind)
        merged (reduce (fn [acc c]
                         (let [k (id c) prev (get acc k)]
                           (assoc acc k
                                  (if prev
                                    (cond-> (assoc prev :seon.ref/depth (max-key strength (:seon.ref/depth prev) (:seon.ref/depth c)))
                                      (not (:seon.ref/resolve-only c)) (dissoc :seon.ref/resolve-only))
                                    c))))
                       {} citations)]
    (into [] (comp (map id) (distinct) (map merged)) citations)))

;; ---- sections -----------------------------------------------------------------------------

(defn- first-sentence
  "The first sentence of flat prose; a period after an abbreviation ends no sentence."
  {:malli/schema [:=> [:cat :string] :string]}
  [flat]
  (let [abbreviations ["e.g." "i.e." "etc." "vs."]
        stop (loop [from 0]
               (let [stop (str/index-of flat ". " from)]
                 (cond (nil? stop) nil
                       (some #(str/ends-with? (subs flat 0 (inc stop)) %) abbreviations) (recur (inc stop))
                       :else stop)))]
    (if stop (subs flat 0 (inc stop)) flat)))

(defn lead-sentence
  "The first sentence of a section's lead paragraph, on one line."
  {:malli/schema [:=> [:cat :string] :string]}
  [lead] (first-sentence (str/join " " (map str/trim (str/split-lines lead)))))

(defn- sections
  "The sections of a scan's blocks, each heading's: its address (path#anchor, anchors as GitHub
  writes them, a repeat suffixed -1, -2), heading, level, line, parent, and its next block's
  text as its lead when a paragraph, else as its opening. One fold with a stack of the open
  sections (v1's with-sections)."
  {:malli/schema [:=> [:cat [:maybe :string] [:vector :seon.ref/block]] [:vector :seon.ref/section]]}
  [path bs]
  (let [nb (count bs)]
    ;; stack: [level index-in-out] of the sections still open
    (loop [i 0 seen {} stack [] out (transient [])]
      (if (= i nb)
        (persistent! out)
        (let [{:seon.ref/keys [kind heading-level heading line]} (nth bs i)]
          (if (not= :heading kind)
            (recur (inc i) seen stack out)
            (let [closing (take-while #(>= (first %) heading-level) (rseq stack))
                  stack (subvec stack 0 (- (count stack) (count closing)))
                  base (slug heading)
                  anchor (if-let [m (seen base)] (str base "-" m) base)
                  address (str path "#" anchor)
                  {nkind :seon.ref/kind body :seon.ref/text} (when (< (inc i) nb) (nth bs (inc i)))]
              (recur (inc i) (assoc seen base (inc (get seen base 0))) (conj stack [heading-level (count out)])
                     (conj! out (cond-> {:seon.ref/address address :seon.ref/anchor anchor :seon.ref/heading heading
                                         :seon.ref/heading-level heading-level :seon.ref/line line}
                                  (seq stack) (assoc :seon.ref/parent (:seon.ref/address (nth out (second (peek stack)))))
                                  (and body (= :para nkind)) (assoc :seon.ref/lead body)
                                  (and body (not= :para nkind)) (assoc :seon.ref/opening body)))))))))))

(defn parse
  "Markdown text at path (nil for a plan or a docstring) read once: its sections, each with
  its own text (the lines after its heading up to the next heading, absent when blank), and
  every citation its text makes, each mention with its depth and line, in order. This is the
  value the docs store keeps; `cited` gives each name once."
  {:malli/schema [:=> [:cat [:maybe :string] :string] :seon.ref/document]}
  [path text]
  (let [{:seon.ref/keys [blocks spans]} (scan text)
        secs (sections path blocks)
        lines (vec (str/split-lines text))
        ends (conj (mapv #(dec (:seon.ref/line %)) (rest secs)) (count lines))]
    {:seon.ref/sections (mapv (fn [s end] (let [t (str/trim (str/join "\n" (subvec lines (min (:seon.ref/line s) end) end)))]
                                            (cond-> s (not (str/blank? t)) (assoc :seon.ref/text t))))
                              secs ends)
     :seon.ref/citations (into [] (keep #(candidate path %)) spans)}))

(defn shown
  "Section s (an address, heading, lead or opening, and the sections below it as address and
  heading) as depth shows it: at :summary its heading and its lead's first sentence; at :doc
  its lead (or opening block) and the sections below; at :code s whole. With no lead it
  answers :no-lead at :summary and :doc. One rule for a section parsed now and a section the
  store holds."
  {:malli/schema [:=> [:cat :seon.ref/view :seon.ref/depth] :seon.ref/view]}
  [s depth]
  (let [{:seon.ref/keys [lead opening below]} s
        head (select-keys s [:seon.ref/address :seon.ref/heading])]
    (if (= :code depth)
      s
      (cond-> (case depth
                :summary (cond-> head lead (assoc :seon.ref/summary (lead-sentence lead)))
                :doc (cond-> (assoc head :seon.ref/below (vec below))
                       lead (assoc :seon.ref/lead lead)
                       opening (assoc :seon.ref/opening opening)))
        (nil? lead) (assoc :seon.ref/problem-kind :no-lead)))))

(defn view
  "The section at address in a parsed document as depth shows it (`shown`), its sections below
  the ones whose parent it is. nil when no section has that address."
  {:malli/schema [:=> [:cat :seon.ref/document :seon.ref/address :seon.ref/depth] [:maybe :seon.ref/view]]}
  [{:seon.ref/keys [sections]} address depth]
  (when-let [s (first (filter #(= address (:seon.ref/address %)) sections))]
    (shown (assoc s :seon.ref/below (into [] (comp (filter #(= address (:seon.ref/parent %)))
                                                   (map #(select-keys % [:seon.ref/address :seon.ref/heading])))
                                          sections))
           depth)))

;; ---- namespaces and files, read when pulled (never stored) --------------------------------

(defn namespace-view
  "Namespace nsym as depth shows it, by the one rule `shown`: its address is its name, its lead
  its docstring and the sections below it its public vars (those of the db, else of the loaded
  namespace). nil when neither db nor process holds it."
  {:malli/schema [:=> [:cat :seon.host/db :symbol :seon.ref/depth] [:maybe :seon.ref/view]]}
  [db nsym depth]
  (let [e (pdb/eid db :ns/name nsym)
        loaded (find-ns nsym)]
    (when (or e loaded)
      (let [doc (if e (:ns/doc (d/pull db [:ns/doc] e)) (:doc (meta loaded)))
            vars (if e
                   (d/q '[:find [?n ...] :in $ ?ns :where [?v :var/ns ?ns] [?v :var/name ?n]] db e)
                   (map #(symbol (str nsym) (str %)) (keys (ns-publics loaded))))]
        (shown (cond-> {:seon.ref/address (str nsym) :seon.ref/heading (str nsym)
                        :seon.ref/below (mapv (fn [v] {:seon.ref/address (str v) :seon.ref/heading (name v)}) (sort vars))}
                 doc (assoc :seon.ref/lead doc))
               depth)))))

(defn- pin
  "The revision main's source pins the reference-code submodule at path to (root's import
  records each gitlink on its :seon.root/source fact), or nil for any other path or when
  root imported no source."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :string]]}
  [db path]
  (let [[top sub] (str/split path #"/")]
    (when (and (= "reference-code" top) sub)
      (get-in (d/pull db [:seon.root/source] [:seon.root/id :root]) [:seon.root/source :pins (str top "/" sub)]))))

(defn file-view
  "File path of the checkout as depth shows it, read now: a Markdown file by the one parse and
  the one rule (`view`, anchor or else its first section; at :code its own text), any
  other file by its address and line count, the text whole at :code. A file of reference-code
  carries the revision main's source pins it at (pin, from db). nil when there is no such
  file or no such anchor."
  {:malli/schema [:=> [:cat :seon.host/db :string :string [:maybe :string] :seon.ref/depth] [:maybe :seon.ref/view]]}
  [db checkout path anchor depth]
  (let [f (io/file checkout path)]
    (when (.isFile f)
      (let [text (slurp f)
            doc (when (str/ends-with? path ".md") (parse path text))
            secs (:seon.ref/sections doc)
            address (if anchor (str path "#" anchor) (:seon.ref/address (first secs)))
            v (if (seq secs)
                (view doc address depth)
                (when-not anchor
                  (cond-> {:seon.ref/address path :seon.ref/heading path :seon.ref/lines (count (str/split-lines text))}
                    (= :code depth) (assoc :seon.ref/text text))))]
        (when v (if-let [p (pin db path)] (assoc v :seon.ref/pin p) v))))))

;; ---- resolution ---------------------------------------------------------------------------

(def problem-messages
  "What each problem kind means and what the agent does about it (v1's seon.ref)."
  {:unresolved "This resolves to nothing on your branch; its operations read what does exist. What the task intends for it is its plan's to say. To mention a name that does not exist, as text and not a reference, wrap it in two backticks: ``ns/name`` or ``:ns/key``."})

(defn names-something?
  "Does citation c name something on db: a var by its :var/name, a namespace by its :ns/name
  or as loaded, a registered schema key, a file of the checkout?"
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] :boolean]}
  [db {:seon.ref/keys [name path]}]
  (boolean (cond (qualified-symbol? name) (seq (d/datoms db :avet :var/name name))
                 (symbol? name) (or (seq (d/datoms db :avet :ns/name name)) (find-ns name))
                 (keyword? name) (contains? contract/forms name)
                 :else (.isFile (io/file (cfg/checkout) path)))))

(defn- alias-form?
  "Is citation c a qualified symbol whose namespace part names no namespace on db or in this
  process, and no var? Then it is an alias form (`str/join`): code, not a reference."
  {:malli/schema [:=> [:cat :seon.host/db :seon.ref/citation] :boolean]}
  [db {:seon.ref/keys [name]}]
  (let [nsym (when (qualified-symbol? name) (symbol (namespace name)))]
    (boolean (and nsym (empty? (d/datoms db :avet :ns/name nsym)) (nil? (find-ns nsym))
                  (empty? (d/datoms db :avet :var/name name))))))

(defn references
  "The references text makes, read on db: its citations once each at their strongest depth,
  a resolve-only one (a prose word) kept only when it names something, an alias form left
  out."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:vector :seon.ref/citation]]}
  [db text]
  (into [] (remove #(or (alias-form? db %) (and (:seon.ref/resolve-only %) (not (names-something? db %)))))
        (cited (:seon.ref/citations (parse nil text)))))

(defn names
  "The qualified symbols text cites, at any depth, once each in first-mention order."
  {:malli/schema [:=> [:cat :string] [:vector :qualified-symbol]]}
  [text]
  (into [] (comp (map :seon.ref/name) (filter qualified-symbol?) (distinct))
        (:seon.ref/citations (parse nil text))))
