#!/bin/zsh
# Build the B1 eval-corpus bundle (nodejs target, :simple — same shape
# as build.sh). Run from this directory.
set -e
cd "$(dirname "$0")"
rm -f out/corpus.js
clj -M -m cljs.main -t nodejs -O simple -o out/corpus.js -c probe.corpus
ls -la out/corpus.js
