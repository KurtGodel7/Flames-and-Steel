#!/bin/bash
# Flames and Steel request 69: AI-only test games, e.g.
#   ./run-ai-test.sh --map flames_and_steel --rounds 15 --games 5 --seed 1 --out ai-test-results
cd "$(dirname "$0")" && ./gradlew -q :game-headless:runAiTest --args="$*"
