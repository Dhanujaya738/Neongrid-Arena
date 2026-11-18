#!/usr/bin/env sh
# Builds NeonGrid with plain javac (JDK 17+, no Maven needed).
# Output: build/classes and build/neongrid-arena.jar
set -e
cd "$(dirname "$0")/.."
rm -rf build
mkdir -p build
find src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
jar --create --file build/neongrid-arena.jar --main-class arena.server.ArenaServer -C build/classes .
echo "Built build/neongrid-arena.jar"
