#!/bin/sh
# Compiles Handler.java and runs the shared postback tests against it.
set -e
cd "$(dirname "$0")"
rm -rf build
javac -d build Handler.java
java -cp build TestHandler.java
