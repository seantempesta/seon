(ns seon.time.instant
  "Portable instant and numeric primitives used by render projections."
  #?(:clj (:import [java.time Instant ZoneId]
                   [java.time.format DateTimeFormatter])))

(def max-safe-integer 9007199254740991)

(defn instant?
  "Whether `value` is a platform instant."
  [value]
  #?(:clj (instance? java.util.Date value)
     :cljs (instance? js/Date value)))

(defn epoch-millis
  "Return the epoch milliseconds represented by `instant`."
  [instant]
  #?(:clj (.getTime ^java.util.Date instant)
     :cljs (.getTime instant)))

(defn from-epoch-millis
  "Construct a platform instant from epoch milliseconds."
  [milliseconds]
  #?(:clj (java.util.Date. (long milliseconds))
     :cljs (js/Date. milliseconds)))

(defn now
  "Return the current platform instant."
  []
  #?(:clj (java.util.Date.)
     :cljs (js/Date.)))

(defn safe-integer?
  "Whether `value` is an integer in the shared exact numeric range."
  [value]
  (and (number? value)
       #?(:clj (and (integer? value)
                    (<= (- max-safe-integer) value max-safe-integer))
          :cljs (js/Number.isSafeInteger value))))

(defn finite-number?
  "Whether `value` is a finite number."
  [value]
  (and (number? value)
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn negative-zero?
  "Whether `value` is IEEE negative zero."
  [value]
  (and (number? value) (zero? value)
       #?(:clj (neg? (Double/doubleToRawLongBits (double value)))
          :cljs (js/Object.is value (js/Number "-0")))))

(defn round
  "Round `value` with the platform's standard nearest-integer operation."
  [value]
  #?(:clj (Math/round (double value))
     :cljs (js/Math.round value)))

(defn hh-mm
  "Format an instant as local 24-hour `HH:mm`, or blank for a non-instant."
  ([instant] (hh-mm instant "UTC"))
  ([instant timezone]
   #?(:clj
      (if (instance? java.util.Date instant)
        (.format (DateTimeFormatter/ofPattern "HH:mm")
                 (.atZone (.toInstant ^java.util.Date instant)
                          (ZoneId/of timezone)))
        "")
      :cljs
      (if (instance? js/Date instant)
        (.toLocaleTimeString
         instant "sv-SE"
         #js {:timeZone timezone :hour "2-digit" :minute "2-digit"})
        ""))))

(defn sv-se-date-time
  "Format an instant as sv-SE-style local date-time in `timezone`."
  [instant timezone]
  #?(:clj
     (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
              (.atZone
               (if (instance? java.util.Date instant)
                 (.toInstant ^java.util.Date instant)
                 ^Instant instant)
               (ZoneId/of timezone)))
     :cljs
     (.toLocaleString instant "sv-SE" #js {:timeZone timezone})))
