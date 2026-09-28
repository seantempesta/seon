(ns seon.print.value
  "The value printer's projection-free core: bounded text of any value and any
  Throwable, from clojure.core alone.

  It requires no Seon namespace, so the low-level namespaces the projection
  loads through (`seon.db`, `seon.schema`, `seon.sci.admit`, `seon.error.refusal`
  and others) and babashka tools print through it too. `seon.render.value` is
  the projection-aware printer on top: the one budget spread over the value's
  shape, breadth before depth, dig forms on the result handle.

  ## Rules

  1. Only clojure.core data prints: EDN scalars, Vars and `clojure.lang`
     collections, walked to `:items` members and `:depth` levels; a lazy seq
     is realized a chunk at most.
  2. Every other object prints as `#object[Class 0xidentity]`, never through
     its print-method, toString or deref.
  3. A string longer than the limit keeps its head; the whole text stops at
     the limit with `…`.
  4. A value whose printing throws prints `#unprintable[value-class failure-class]`.
  5. A Throwable prints each link of its cause chain once (class, message,
     ex-data, the frames nearest the throw), each list counting what it left out.

  ## Costs

  - A map holding a 5M-character string, a 200k vector and an SCI context:
    401 characters in 334 µs (print-owners, 2026-09-27)."
  (:require [clojure.main :as main]
            [clojure.string :as str]))

(def ^:private text-limits
  {:chars 400 :items 8 :depth 3 :entries 12 :links 8 :frames 10 :suppressed 3 :throwable-chars 8192})

(defn- datum
  "`x` as clojure.core data at most `level` collections deep (rules 1–3):
  `...` marks a cut collection or its omitted members; a string past `limit`
  keeps its head."
  {:malli/schema [:=> [:cat :seon.schema/value :int :int] :seon.schema/value]}
  [x level limit]
  (let [items (:items text-limits)]
    (cond
      (string? x) (if (> (count x) limit) (str (subs x 0 limit) "…") x)
      (or (nil? x) (boolean? x) (number? x) (char? x) (ident? x) (var? x)
          (uuid? x) (instance? java.util.Date x)) x
      (not (and (coll? x) (str/starts-with? (.getName (class x)) "clojure.lang.")))
      (symbol (str "#object[" (.getName (class x)) " 0x" (Integer/toHexString (System/identityHashCode x)) "]"))
      (zero? level) '...
      :else (let [walk #(datum % (dec level) limit)
                  members (cond-> (mapv (if (map? x) (fn [[k v]] [(walk k) (walk v)]) walk) (take items x))
                            (> (bounded-count (inc items) x) items) (conj (if (map? x) ['... '...] '...)))]
              (cond (map? x) (into {} members) (vector? x) members
                    (set? x) (set members) :else (apply list members))))))

(defn bounded-text
  "`value` as at most `limit` characters (default 400).

  ## Edge cases

  - A 200-deep map prints as `{:k 199, :v {:k 198, :v {:k 197, :v ...}}}`.
  - A host object prints as `#object[Class 0xhash]`, never through its
    `toString`, print-method or `deref`."
  {:malli/schema [:function [:=> [:cat :seon.schema/value] :string]
                  [:=> [:cat :seon.schema/value [:int {:min 1}]] :string]]}
  ([value] (bounded-text value (:chars text-limits)))
  ([value limit]
   (try
     (let [text (binding [*print-length* nil *print-level* nil *print-meta* false *print-readably* true
                          *print-dup* false *print-namespace-maps* false]
                  (pr-str (datum value (:depth text-limits) limit)))]
       (if (> (count text) limit) (str (subs text 0 limit) "…") text))
     (catch Throwable failure
       (str "#unprintable[" (.getName (class value)) " " (.getName (class failure)) "]")))))

(defn- causes
  "`throwable` and its causes, outermost first, each once, at most `:links`."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/throwable]]}
  [throwable]
  (loop [link throwable seen []]
    (if (or (nil? link) (= (:links text-limits) (count seen)) (some #(identical? link %) seen))
      seen
      (recur (.getCause ^Throwable link) (conj seen link)))))

(defn- link-lines
  "One link's lines: class and message, ex-data entries, the frames nearest the
  throw and suppressed throwables, each list counting what it leaves out; a
  link whose accessors throw prints its class and the failure's."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :string]]}
  [^Throwable link]
  (let [{:keys [entries frames suppressed]} text-limits]
    (try
      (let [trace (vec (.getStackTrace link))]
        (into [(str (.getName (class link)) ": " (bounded-text (ex-message link)))]
              (concat
               (for [[k v] (take entries (ex-data link))] (str "  " (bounded-text k) " " (bounded-text v)))
               (when (> (bounded-count (inc entries) (ex-data link)) entries) ["  … further ex-data entries"])
               (for [^StackTraceElement f (take frames trace)]
                 (str "  at " (main/demunge (.getClassName f)) " (" (.getFileName f) ":" (.getLineNumber f) ")"))
               (when (> (count trace) frames)
                 [(str "  … " (- (count trace) frames) " further frames")])
               (for [^Throwable other (take suppressed (.getSuppressed link))]
                 (str "  suppressed " (.getName (class other)) ": " (bounded-text (ex-message other)))))))
      (catch Throwable failure
        [(str (.getName (class link)) " #unprintable[" (.getName (class failure)) "]")]))))

(defn throwable-text
  "An unhandled Throwable as bounded text from the Throwable alone: one block
  of lines per cause, each link walked once by identity, so a cyclic chain
  terminates. Which frames matter is the error render's to say.

  ## Costs

  - A 13-link `ex-info` chain with a 50-item payload per link (714 characters
    out): 783 µs unarmed, 977 µs armed (default, 2026-09-26)."
  {:malli/schema [:=> [:cat :seon.error/throwable] :string]}
  [throwable]
  (let [chain (causes throwable)
        text (str/join "\n" (concat (mapcat (fn [position link]
                                              (cond-> (link-lines link) (pos? position) (update 0 #(str "caused by " %))))
                                            (range) chain)
                                    (when (and (= (:links text-limits) (count chain)) (ex-cause (peek chain)))
                                      ["… further causes"])))]
    (if (> (count text) (:throwable-chars text-limits))
      (str (subs text 0 (:throwable-chars text-limits)) "…")
      text)))
