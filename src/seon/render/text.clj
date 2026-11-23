(ns seon.render.text
  "Zero-dependency single-source Hiccup -> compact text/Markdown projection.
   The ONE implementation behind `seon.render/hiccup->text`,
   `seon.render.fit.measure/hiccup->text-fallback` and `seon.render.print.emit/hiccup->md`."
  (:require [clojure.string :as str]))
