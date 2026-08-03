(ns seon.web.extract
  "Derived text projections over already-captured web response bytes."
  (:import [java.io ByteArrayInputStream]
           [org.jsoup Jsoup]))

(defn- html
  [octets charset-name base-url]
  (let [document (Jsoup/parse (ByteArrayInputStream. ^bytes octets)
                              charset-name base-url)
        title (.title document)
        text (.text document)]
    (cond-> {:my.web.extract/text text}
      (not (empty? title))
      (assoc :my.web.extract/title title))))
