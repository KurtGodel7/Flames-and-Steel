#!/bin/bash
# Flames and Steel playtest release (Mac and Linux): checks Java, installs the map, starts the game.
# The Mac "Run Flames and Steel.command" runs this same file.
NEED=25
HERE="$(cd "$(dirname "$0")" && pwd)"

say() {
  echo "$1"
  if [ "$(uname)" = "Darwin" ]; then
    osascript -e "display dialog \"$1\" buttons {\"OK\"} default button 1 with title \"Flames and Steel\"" >/dev/null 2>&1
  elif command -v zenity >/dev/null 2>&1; then
    zenity --info --title="Flames and Steel" --text="$1" >/dev/null 2>&1
  fi
}

# --- 1. Java -------------------------------------------------------------------
JAVA=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif [ "$(uname)" = "Darwin" ] && /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA="$(/usr/libexec/java_home)/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
fi
JVER=""
if [ -n "$JAVA" ]; then
  JVER=$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
fi
MAJOR=${JVER%%.*}
if [ -z "$JVER" ] || [ "$MAJOR" = "1" ] || [ "$MAJOR" -lt "$NEED" ] 2>/dev/null; then
  if [ -z "$JVER" ]; then HAVE="Java was not found on this computer."; else HAVE="This computer has Java $JVER."; fi
  say "Flames and Steel needs Java $NEED or newer. $HAVE Install Temurin $NEED from https://adoptium.net (Mac: the .pkg installer; aarch64 for Apple M1/M2/M3 and later, x64 for older Intel Macs), then start the game again."
  if [ "$(uname)" = "Darwin" ]; then open "https://adoptium.net/temurin/releases/?version=$NEED"; fi
  exit 1
fi
echo "Java $JVER found."

# --- 2. Map ---------------------------------------------------------------------
# The game reads maps from ~/Documents/triplea if that folder exists, otherwise from ~/triplea.
ROOT="$HOME/triplea"
[ -d "$HOME/Documents/triplea" ] && ROOT="$HOME/Documents/triplea"
MAPS="$ROOT/downloadedMaps"
mkdir -p "$MAPS"
echo "Installing the Flames and Steel map into $MAPS/flames_and_steel ..."
# replaces only the flames_and_steel folder; other maps and saved games are not touched
rm -rf "$MAPS/flames_and_steel"
if ! cp -R "$HERE/flames_and_steel" "$MAPS/flames_and_steel"; then
  say "Could not copy the map into $MAPS. Close the game if it is running, then try again."
  exit 1
fi

# --- 3. Start -------------------------------------------------------------------
cd "$HERE/game" || exit 1
JAR=$(ls bin/game-headed-*.jar 2>/dev/null | tail -n 1)
if [ -z "$JAR" ]; then
  say "The game file is missing from the game/bin folder. Unzip the whole release again."
  exit 1
fi
echo "Starting Flames and Steel. Errors are written to $HERE/game/console.log"
exec "$JAVA" -jar "$JAR" > console.log 2>&1
