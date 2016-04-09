#!/bin/bash

set -e

OUTPUT=${1:-"/tmp/buck"}
cd $( dirname "${BASH_SOURCE[0]}" )/..

# Build local Buck
ant
touch build/successful-build
./bin/buck build buck

# Find the Buck executable
BIN_FILE=$(./bin/buck targets --show_output buck | grep ":buck" | cut -d' ' -f2)
[ -n "$BIN_FILE" ] || ( echo "ERROR: Failed to build buck binary"; exit 1 )

# Copy executable to output file
cp $BIN_FILE $OUTPUT
echo "Buck binary: $OUTPUT"
