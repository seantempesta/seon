#!/bin/zsh
# Build the sci probe bundle (nodejs target, :simple — same shape as the
# sci ADR harness). Run from this directory.
set -e
cd "$(dirname "$0")"
rm -rf out/main.js out/cljs
clj -M -m cljs.main -t nodejs -O simple -o out/main.js -c probe.main
ls -la out/main.js
