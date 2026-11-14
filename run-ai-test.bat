@echo off
rem Flames and Steel request 69: AI-only test games, e.g.
rem   run-ai-test --map flames_and_steel --rounds 15 --games 5 --seed 1 --out ai-test-results
rem Options: --xml <game.xml>  --parallel N  --round-minutes M  --heap 3g
cd /d "%~dp0"
call gradlew.bat -q :game-headless:runAiTest --args="%*"
