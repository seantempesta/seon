(ns seon.time.instant
  "Portable formatting for immutable instants used by render projections."
  #?(:clj (:import [java.time Instant ZoneId]
                   [java.time.format DateTimeFormatter])))

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
