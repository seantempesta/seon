(ns seon.render.canvas.field-signal
  "Portable encoding for Datastar canvas field signal identifiers."
  #?(:cljs (:require ["node:buffer" :refer [Buffer]]))
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.util Base64])))

(def ^:private prefix "seon_")

(defn field-signal
  "Encode a qualified field keyword as the canonical base64url signal."
  [field]
  (str prefix
       #?(:clj
          (.encodeToString
           (.withoutPadding (Base64/getUrlEncoder))
           (.getBytes (str field) StandardCharsets/UTF_8))
          :cljs
          (.toString (.from Buffer (str field) "utf8") "base64url"))))
