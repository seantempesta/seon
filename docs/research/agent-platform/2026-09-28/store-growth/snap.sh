#!/bin/zsh
# usage: snap.sh marker  -> prints count/bytes of files newer than marker
find /Users/sean/src/seon/data/store -type f -newer $1 -exec stat -f '%z' {} + | awk '{s+=$1;n++} END{print "newer files",n+0,"bytes",s+0}'
