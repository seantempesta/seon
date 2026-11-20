(ns seon.render.chronicle
  "Time- and Datahike-tx-anchored Chronicle summary stream, tx-window distillation, compaction timeline builder, freshness triggers, and Living Canvas data engine."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.health :as health]
            [seon.health.metrics :as hmetrics]))
