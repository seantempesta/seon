(ns seon.reader
  "Reading source as data, for analysis (edamame): the program's definitions as the loader
  and the reach walk see them, and a task's references. Evaluation reads with Jolt's own
  reader (seon.repl); this reader never evaluates anything: tagged literals, `::` keywords
  and reader conditionals all read as plain data."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [edamame.core :as e] [clojure.string :as str]))

(defn line-starts
  "The offset in text where each line starts: 0, then one past each newline."
  {:malli/schema [:=> [:cat :string] [:vector :int]]}
  [^String text]
  (loop [acc [0] i 0]
    (let [j (.indexOf text "\n" (int i))]
      (if (neg? j) acc (recur (conj acc (inc j)) (inc j))))))

(defn lead-end
  "Where the first form starts in text from i to end: past whitespace, commas and ; comments."
  {:malli/schema [:=> [:cat :string :int :int] :int]}
  [^String text i end]
  (loop [i i]
    (if (>= i end)
      end
      (let [c (.charAt text i)]
        (cond (or (Character/isWhitespace c) (= c \,)) (recur (inc i))
              (= c \;) (let [j (.indexOf text "\n" (int i))] (recur (if (or (neg? j) (> j end)) end (inc j))))
              :else i)))))

(defn lead
  "The ; comments before the form that ends at end in text, read from i (where the form
  before it ended): the text between them, trimmed, when it holds a comment, else nil. A
  comment on the line the form before ended on is that form's, so the lead starts on the
  next line. A form's lead is what its writer said before it: a definition's (:form/lead),
  and an evaluation's thought."
  {:malli/schema [:=> [:cat :string :int :int] [:maybe :string]]}
  [^String text i end]
  (let [nl (.indexOf text "\n" (int i))
        i (if (and (pos? i) (<= 0 nl) (< nl end)
                   (let [s (str/triml (subs text i nl))] (or (= "" s) (str/starts-with? s ";"))))
            (inc nl)
            i)
        l (str/trim (subs text i (lead-end text i end)))]
    (when (str/includes? l ";") l)))

(defn opts
  "edamame's options for reading source written in namespace nsym: every Clojure syntax
  (`#()`, regex, var, syntax-quote, `#=` as data), `::kw` resolved against nsym (an alias
  resolves to itself), any tag read as its form, and the :clj branch of a reader
  conditional."
  {:malli/schema [:=> [:cat :symbol] :map]}
  [nsym]
  {:all true :read-eval false :read-cond :allow :features #{:clj}
   :auto-resolve (fn [a] (if (= :current a) nsym a))
   :readers (fn [_] identity)})

(defn read-all
  "Every form of text, written in namespace nsym, in order."
  {:malli/schema [:function [:=> [:cat :string :symbol] [:vector :any]] [:=> [:cat :string :symbol [:maybe :map]] [:vector :any]]]}
  ([text nsym] (read-all text nsym nil))
  ([text nsym more] (e/parse-string-all text (merge (opts nsym) more))))

(defn read-text
  "Every form of text written in namespace nsym, or nil when text does not read as Clojure
  (prose, an unbalanced span). The text is a model's or a plan's, so not reading is an
  answer, not a failure; nothing is evaluated (no #=), so whatever the read throws means
  that. edamame 1.4.32 on Jolt throws a bare IllegalArgumentException for a meta prefix at
  the end (\"a^\"), not its :edamame/error (docs/failure-points.md)."
  {:malli/schema [:function [:=> [:cat :string :symbol] [:maybe [:vector :any]]] [:=> [:cat :string :symbol [:maybe :map]] [:maybe [:vector :any]]]]}
  ([text nsym] (read-text text nsym nil))
  ([text nsym more]
   (try (read-all text nsym more)
        (catch Throwable _ nil))))

(defn definition-head
  "Definition text src (a defn's, written in namespace nsym) up to its body, as written: its
  name, docstring, attribute map and arglist, or per arity its arglist, each arity's body and
  the definition's end marked `…)`; nil when src is not a defn, defn-, defmacro or fn (a def, a
  defmulti: no arglist). One read, by edamame's end locations."
  {:malli/schema [:=> [:cat :string :symbol] [:maybe :string]]}
  [src nsym]
  (let [f (first (read-text src nsym {:end-location true}))
        starts (line-starts src)
        at (fn [row col] (+ (nth starts (dec row)) (dec col)))
        end (fn [x] (let [{:keys [end-row end-col]} (meta x)] (when end-row (at end-row end-col))))
        start (fn [x] (let [{:keys [row col]} (meta x)] (when row (at row col))))
        fn? (and (seq? f) ('#{defn defn- defmacro fn} (first f)))
        params (when fn? (some #(when (vector? %) %) (drop 2 f)))
        arities (when fn? (filter #(and (seq? %) (vector? (first %))) (drop 2 f)))]
    (cond
      (and params (end params)) (str (subs src 0 (end params)) "\n  …)")
      (and (seq arities) (start (first arities)))
      (str (str/trimr (subs src 0 (start (first arities))))
           (apply str (for [a arities :let [p (first a)] :when (and (start a) (end p))]
                        (str "\n  " (subs src (start a) (end p)) " …)")))
           ")"))))

(defn arglists
  "The arglists definition text src declares (a defn's, defn-'s or defmacro's, written in
  namespace nsym): its params vector, or each arity's; nil when it is no such definition. One
  read: what a var's arglists are, from its recorded source rather than a process's var meta."
  {:malli/schema [:=> [:cat :string :symbol] [:maybe [:sequential vector?]]]}
  [src nsym]
  (let [f (first (read-text src nsym))]
    (when (and (seq? f) ('#{defn defn- defmacro} (first f)))
      (if-let [params (some #(when (vector? %) %) (drop 2 f))]
        (list params)
        (seq (keep #(when (and (seq? %) (vector? (first %))) (first %)) (drop 2 f)))))))

;; ---- the forgiving reader: a model's reply as source (idea 8) ----------------------------
;;
;; A model's reply mixes prose and code. `forgive` makes it source a REPL reads: prose
;; becomes comments, and unbalanced delimiters are repaired, each change a repair
;; {:at :dropped :inserted}: at offset :at of the text as the changes before it left it,
;; :dropped was replaced by :inserted. Two laws hold (Seon's spec, T5's forgive tasks):
;;   - forgiving forgiven text changes nothing (\"a]a\" never becomes \"aa\");
;;   - the repairs account for every change: undone in reverse, they give back the reply.


(def ^:private openers {\( \) \[ \] \{ \}})
(def ^:private closers {\) \( \] \[ \} \{})
(def ^:private code-tags #{"clojure" "clj" "cljc" "edn"})

(defn- fence
  "The tag of a fence line (``` first, after blanks and a ;; this reader wrote), \"\" when
  untagged, else nil."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [line]
  (let [t (str/triml (if (str/starts-with? line ";; ") (subs line 3) line))]
    (when (str/starts-with? t "```")
      (str/lower-case (first (str/split (str/trim (str/replace t #"^`+" "")) #"\s+"))))))

(defn- literal-line?
  "Is trimmed line t exactly one literal (number, string, keyword, character, true, false,
  nil) or one qualified symbol? Read without syntax-quote (a literal holds none, and edamame
  expands nested ones exponentially: a run of backticks in a reply read as a loop).
  See docs/failure-points.md."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [t]
  (let [fs (read-text t 'user {:syntax-quote false}) x (first fs)]
    (boolean (and (= 1 (count fs))
                  (or (number? x) (string? x) (keyword? x) (char? x) (boolean? x) (nil? x) (qualified-symbol? x))))))

(defn- starts-form?
  "Does trimmed line t start a form by its first character: a collection, a string, a
  quote, deref or meta character, # before a dispatch character (a letter, { ( \" ' _ ? :
  ^ =; never # before a blank, a heading), a backtick before a collection, or a lone
  backslash (a character not yet written)."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [t]
  (let [c (first t) d (second t)]
    (boolean (case c
               (\( \[ \{ \' \@ \^ \") true
               \# (and d (or (Character/isLetter ^char d) (#{\{ \( \" \' \_ \? \: \^ \=} d)))
               \` (contains? openers d)
               \\ (= "\\" t)
               false))))

(defn- scan
  "One pass over a reply, line by line, with one stack of open delimiters (outside strings,
  character literals and comments; a backslash anywhere in a token starts a character literal,
  which takes the next character whatever it is, so `\\( is one form). A line is:
  - a fence line (``` first): it becomes a comment (`;; `), and is still known as a fence
    when read again. A clojure/clj/cljc/edn fence's body is code; another tag's is prose;
    an untagged one's (models fence prose too) goes by the rule below, as outside fences;
  - code: inside an open string; in a code fence; indented or starting with a closer
    inside an open form; or starting a form, or being one literal or qualified symbol;
  - blank or a comment: it stays;
  - else prose: it becomes a comment (`;; ` inserted at its start).
  In code, a closer that matches no open delimiter becomes a comma (whitespace to the
  reader, so \"a]a\" never joins into \"aa\", and never the start of a fence line); a closer
  that matches a deeper open delimiter first closes the ones above it. With col0?, a code
  line starting in column zero (not indented, not a closer) first closes every open
  delimiter. At the end an open string closes (\\\" after a lone backslash), then every open
  delimiter closes. An inserted closer goes right after the last code token before it (so
  a line stays inside the form it continued however often it is read), but before a lone
  backslash, which would read it as a character.
  Each change is a repair {:at :dropped :inserted} at offset :at of the text as the changes
  before it left it. Answers {:text :repairs :delimiters n} (n: the repairs to delimiters)."
  {:malli/schema [:=> [:cat :string :boolean] [:map [:text :string] [:repairs [:vector :map]] [:delimiters :int]]]}
  [^String text col0?]
  (let [out (StringBuilder.)
        delim? (fn [c] (or (Character/isWhitespace ^char c) (= c \,) (openers c) (closers c) (= c \") (= c \;)))
        end! (fn [st] (assoc st :code-end (.length out)))
        ins (fn [st s] (let [p (:code-end st)]
                         (.insert out (int p) ^String s)
                         (-> st (update :repairs conj {:at p :dropped "" :inserted s}) (update :delimiters inc)
                             (assoc :code-end (+ p (count s))))))
        close-all (fn [st] (-> (ins st (apply str (map openers (rseq (:stack st))))) (assoc :stack [])))
        ;; the characters of one code line (no newline), through strings, comments and tokens
        code (fn [st ^String line]
               (let [n (count line)]
                 (loop [i 0 st st]
                   (if (>= i n)
                     st
                     (let [c (.charAt line (int i))]
                       (cond
                         (:in-str st) (do (.append out c)
                                          (recur (inc i) (cond (:esc st) (assoc st :esc false)
                                                               (= c \\) (assoc st :esc true)
                                                               (= c \") (end! (assoc st :in-str false))
                                                               :else st)))
                         (= c \;) (do (.append out (subs line i)) st)
                         (or (Character/isWhitespace c) (= c \,)) (do (.append out c) (recur (inc i) st))
                         (= c \") (do (.append out c) (recur (inc i) (assoc st :in-str true :esc false)))
                         (openers c) (do (.append out c) (recur (inc i) (end! (update st :stack conj c))))
                         (closers c)
                         (let [stack (:stack st)
                               j (some #(when (= (closers c) (stack %)) %) (reverse (range (count stack))))]
                           (if (nil? j)
                             (let [p (.length out)]
                               (.append out ",")
                               (recur (inc i) (-> st (update :repairs conj {:at p :dropped (str c) :inserted ","}) (update :delimiters inc))))
                             (let [st (if (< j (dec (count stack)))
                                        (-> (ins st (apply str (map openers (rseq (subvec stack (inc j))))))
                                            (assoc :stack (subvec stack 0 (inc j))))
                                        st)]
                               (.append out c)
                               (recur (inc i) (end! (update st :stack pop))))))
                         :else
                         ;; a token runs to the next delimiter; a backslash anywhere in it starts a
                         ;; character literal, which takes the next character whatever it is (`\( is
                         ;; syntax-quote and the character (, never a quote and an open paren)
                         (let [[end lone?] (loop [k i]
                                             (cond (>= k n) [k false]
                                                   (= \\ (.charAt line (int k))) (if (< (inc k) n) (recur (+ k 2)) [n true])
                                                   (delim? (.charAt line (int k))) [k false]
                                                   :else (recur (inc k))))
                               tok (subs line i end)]
                           (.append out tok)
                           ;; a closer after a lone backslash (one ending the line) would be read as its
                           ;; character: it goes right before that backslash, after the rest of the token
                           (recur end (if lone? (assoc st :code-end (dec (.length out))) (end! st))))))))))
        lines (str/split text #"\n" -1)]
    (loop [k 0 st {:stack [] :repairs [] :delimiters 0 :code-end 0 :in-str false :esc false :fenced nil}]
      (if (= k (count lines))
        (let [st (if (:in-str st)
                   (let [s (if (:esc st) "\\\"" "\"") p (.length out)]
                     (.append out s)
                     (-> st end! (update :repairs conj {:at p :dropped "" :inserted s}) (update :delimiters inc)))
                   st)
              st (if (seq (:stack st)) (close-all st) st)]
          {:text (str out) :repairs (:repairs st) :delimiters (:delimiters st)})
        (let [line (nth lines k)
              _ (when (pos? k) (.append out \newline))
              t (str/replace line #"^[\s,]+" "")
              indented? (not= t line)
              tag (when-not (:in-str st) (fence line))
              kind (cond tag :fence
                         (:in-str st) :code
                         (or (str/blank? t) (str/starts-with? t ";")) :keep
                         (= :code (:fenced st)) :code
                         (= :prose (:fenced st)) :prose
                         (and (seq (:stack st)) (or indented? (contains? closers (first t)))) :code
                         (or (starts-form? t) (literal-line? t)) :code
                         :else :prose)
              comment! (fn [st] (let [p (.length out)]
                                  (.append out ";; ") (.append out line)
                                  (update st :repairs conj {:at p :dropped "" :inserted ";; "})))
              st (case kind
                   :fence (-> (if (str/starts-with? line ";; ") (do (.append out line) st) (comment! st))
                              (assoc :fenced (if (:fenced st) nil (cond (code-tags tag) :code (= "" tag) :lines :else :prose))))
                   :keep (do (.append out line) st)
                   :prose (comment! st)
                   :code (code (if (and col0? (not (:in-str st)) (seq (:stack st)) (not indented?) (not (closers (first t))))
                                 (close-all st)
                                 st)
                               line))]
          (recur (inc k) st))))))

(defn forgive
  "A model's reply as source a REPL reads, and every change made to it (scan): prose
  becomes comments and delimiters are repaired; a reply whose delimiters balance gets no
  column-zero closes. Answers {:text :repairs}."
  {:malli/schema [:=> [:cat :string] [:map [:text :string] [:repairs [:vector [:map [:at :int] [:dropped :string] [:inserted :string]]]]]]}
  [reply]
  (let [r (scan reply false)
        r (if (pos? (:delimiters r)) (scan reply true) r)]
    (select-keys r [:text :repairs])))

(defn delimiters
  "Code text s's delimiters left unpaired, read as `balance` reads them (strings, character literals and ;
  comments aside): :open, each opener no closer closed, [char offset], outermost first; :stray, each closer
  with no opener open, [char offset]; :crossed, each closer of another kind than the opener it meets,
  [closer offset opener opener-offset]. What a refused or failed edit names by line, so a slip is found where it
  is (a refusal that says how many but not where leaves the agent counting parens).
  See docs/agent.md#changing-a-definition."
  {:malli/schema [:=> [:cat :string] [:map [:open [:vector [:tuple char? nat-int?]]] [:stray [:vector [:tuple char? nat-int?]]]
                                       [:crossed [:vector [:tuple char? nat-int? char? nat-int?]]]]]}
  [^String s]
  (let [n (count s) by-closer (into {} (map (fn [[o c]] [c o])) openers)]
    (loop [i 0 in-str false stack [] stray [] crossed []]
      (if (>= i n)
        {:open stack :stray stray :crossed crossed}
        (let [c (.charAt s (int i))]
          (cond
            in-str (cond (= c \\) (recur (+ i 2) true stack stray crossed) (= c \") (recur (inc i) false stack stray crossed) :else (recur (inc i) true stack stray crossed))
            (= c \") (recur (inc i) true stack stray crossed)
            (= c \\) (recur (+ i 2) false stack stray crossed)
            (= c \;) (recur (or (some-> (str/index-of s "\n" i) inc) n) false stack stray crossed)
            (openers c) (recur (inc i) false (conj stack [c i]) stray crossed)
            (by-closer c) (if-let [[o oi] (peek stack)]
                            (recur (inc i) false (pop stack) stray (if (= o (by-closer c)) crossed (conj crossed [c i o oi])))
                            (recur (inc i) false stack (conj stray [c i]) crossed))
            :else (recur (inc i) false stack stray crossed)))))))

(defn delimiter-counts
  "Unpaired delimiters d (as `delimiters` answers them) as a count each: how many more ( [ { were opened
  than ) ] } closed, {\\( n \\[ n \\{ n}. See docs/agent.md#changing-a-definition."
  {:malli/schema [:=> [:cat [:map [:open :any] [:stray :any] [:crossed :any]]] [:map-of char? :int]]}
  [{:keys [open stray crossed]}]
  (let [by-closer (into {} (map (fn [[o c]] [c o])) openers)]
    (as-> {\( 0 \[ 0 \{ 0} acc
      (reduce (fn [m [c]] (update m c inc)) acc open)
      (reduce (fn [m [c]] (update m (by-closer c) dec)) acc stray)
      ;; a closer of another kind closes its opener and stands for its own kind's
      (reduce (fn [m [c _ o]] (-> m (update o inc) (update (by-closer c) dec))) acc crossed))))

(defn balance
  "Code text s's delimiters as a count each: how many more ( [ { it opens than ) ] } it closes,
  {\\( n \\[ n \\{ n}, outside strings, character literals (a backslash takes the next character,
  so `\\)` is no closer) and ; comments: `delimiters`, the one scan, counted (`delimiter-counts`). See
  docs/agent.md#changing-a-definition."
  {:malli/schema [:=> [:cat :string] [:map-of char? :int]]}
  [^String s]
  (delimiter-counts (delimiters s)))
