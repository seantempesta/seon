(ns seon.web.reactive.encoding
  "Signal encoding: Clojure keywords <-> Datastar signal paths.

   The single layer for converting between Clojure qualified keywords and
   Datastar-compatible signal names. All signal name manipulation goes through
   this namespace — nothing else should do its own encoding/decoding.

   Encoding strategy:
   - Namespace dots become signal path dots (nested segments)
   - Namespace hyphens become camelCase (matching Datastar's own conversion)
   - The slash between namespace and name becomes a dot
   - The name part is camelCased

   Example:
     :seon.getting-started/exercise → seon.gettingStarted.exercise
     :seon.ctx/user-input          → seon.ctx.userInput

   On the wire, Datastar sends nested JSON:
     {\"seon\": {\"gettingStarted\": {\"exercise\": \"Pull-up\"}}}

   Decoding flattens this back to qualified keywords, reversing camelCase
   per segment.

   Note: This namespace uses positional arguments rather than map-in/map-out
   because it's a pure transformation library where (encode-keyword kw)
   is more natural than (encode-keyword {::kw kw}). Same convention as
   seon.web.reactive.transform."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::signal-path
                  [:string {:min 1
                            :description "Datastar signal path e.g. seon.gettingStarted.exercise"}])

;;; ---------------------------------------------------------------------------
;;; camelCase <-> kebab-case
;;; ---------------------------------------------------------------------------

(defn kebab->camel
  "Convert kebab-case to camelCase.
   'getting-started' → 'gettingStarted'
   'user-input' → 'userInput'"
  [s]
  (let [parts (str/split s #"-")]
    (str (first parts)
         (str/join (map str/capitalize (rest parts))))))

(defn camel->kebab
  "Convert camelCase to kebab-case.
   'gettingStarted' → 'getting-started'
   'userInput' → 'user-input'"
  [s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case))

;;; ---------------------------------------------------------------------------
;;; Encode: Keyword -> Signal Path
;;; ---------------------------------------------------------------------------

(defn encode-keyword
  "Convert a Clojure keyword to a Datastar signal path.

   Qualified keywords:
     :seon.getting-started/exercise → 'seon.gettingStarted.exercise'
     :seon.ctx/user-input           → 'seon.ctx.userInput'

   Unqualified keywords:
     :exercise → 'exercise'

   The namespace is split on dots. Each segment is camelCased.
   The name part is camelCased. All joined with dots."
  {:malli/schema [:=> [:cat :keyword] ::signal-path]}
  [kw]
  (if-let [ns (namespace kw)]
    (let [ns-segments (str/split ns #"\.")
          camel-segments (mapv kebab->camel ns-segments)
          camel-name (kebab->camel (name kw))]
      (str (str/join "." camel-segments) "." camel-name))
    (kebab->camel (name kw))))

;;; ---------------------------------------------------------------------------
;;; Decode: Signal Data -> Qualified Keywords
;;; ---------------------------------------------------------------------------

(defn decode-signals
  "Decode a Datastar POST body (nested or flat JSON map) to qualified keywords.

   Nested JSON (from dot-notation signals):
     {\"seon\" {\"gettingStarted\" {\"exercise\" \"Pull-up\"}}}
     → {:seon.getting-started/exercise \"Pull-up\"}

   Flat JSON with camelCase keys (legacy Datastar format):
     {\"userInput\" \"hello\"}
     → {:user-input \"hello\"}

   The algorithm walks the nested structure. Leaf values reconstruct
   the keyword: all path segments except the last form the namespace
   (joined with dots, each reverse-camelCased), the last segment is
   the name (reverse-camelCased)."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map-of :keyword :any]]}
  [body]
  (if-not (map? body)
    {}
    (letfn [(flatten-map [path m]
              (if (map? m)
                (mapcat (fn [[k v]]
                          (flatten-map (conj path (if (string? k) k (name k))) v))
                        m)
                ;; Leaf: reconstruct keyword from path
                (let [segments (mapv camel->kebab path)]
                  (if (> (count segments) 1)
                    ;; Qualified: all but last = namespace, last = name
                    (let [ns-str (str/join "." (butlast segments))
                          nm (last segments)]
                      [[(keyword ns-str nm) m]])
                    ;; Unqualified: single segment
                    [[(keyword (first segments)) m]]))))]
      (into {} (flatten-map [] body)))))

;;; ---------------------------------------------------------------------------
;;; Signals JSON: Build data-signals attribute value
;;; ---------------------------------------------------------------------------

(defn- build-nested
  "Build a nested map from a flat map of signal-path -> value.
   'seon.gettingStarted.exercise' -> {\"seon\" {\"gettingStarted\" {\"exercise\" val}}}"
  [flat-map]
  (reduce
   (fn [acc [path val]]
     (let [segments (str/split path #"\.")]
       (assoc-in acc segments val)))
   {}
   flat-map))

(defn encode-signals-json
  "Build a JSON string for the data-signals attribute from a map of
   {keyword default-value}.

   Example:
     {:seon.getting-started/exercise \"\"}
     → '{\"seon\":{\"gettingStarted\":{\"exercise\":\"\"}}}'

   Unqualified keywords produce flat keys:
     {:exercise \"\"} → '{\"exercise\":\"\"}'
   "
  {:malli/schema [:=> [:cat [:map-of :keyword :any]] :string]}
  [field-defaults]
  (let [flat (into {} (map (fn [[kw val]]
                             [(encode-keyword kw) val])
                           field-defaults))
        nested (build-nested flat)]
    (json/generate-string nested)))

(comment
  ;; Encode
  (encode-keyword :seon.getting-started/exercise)
  ;; => "seon.gettingStarted.exercise"

  (encode-keyword :seon.ctx/user-input)
  ;; => "seon.ctx.userInput"

  (encode-keyword :exercise)
  ;; => "exercise"

  ;; Decode
  (decode-signals {"seon" {"gettingStarted" {"exercise" "Pull-up"}}})
  ;; => {:seon.getting-started/exercise "Pull-up"}

  (decode-signals {"userInput" "hello"})
  ;; => {:user-input "hello"}

  ;; Signals JSON
  (encode-signals-json {:seon.getting-started/exercise ""
                         :seon.ctx/user-input ""})

  nil)
