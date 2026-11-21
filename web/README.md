# web/

The live Seon Web UI (`http://127.0.0.1:9998/`) is implemented directly in Clojure under `src/seon/web*.clj` and `src/seon/render/*.clj` and documented in [docs/render.md](../docs/render.md).

Run the standing server with `bin/seon start` (or launch the embedded macOS `WKWebView` window with `bin/seon browser`).

The `web/bench/` and `web/shots/` subdirectories hold legacy pre-rebuild measurement scripts and reference screenshots retained for historical comparison.
