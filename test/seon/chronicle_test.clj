(ns seon.chronicle-test
  "Fast (< 50 ms) unit tests for `:seon.summary/*` Malli->Datahike schema derivation, `seon.render.chronicle`
   tx-range distillation, compaction timeline budgeting, freshness triggers, Living Canvas UI, and `summary-tile`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.render :as render]
            [seon.render.canvas :as canvas]
            [seon.render.canvas.blocks :as cblocks]
            [seon.render.chronicle :as chronicle]
            [seon.render.tiles :as tiles]
            [seon.schema :as schema]
            [seon.schema.datahike :as dh]))
