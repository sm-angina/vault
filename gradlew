#!/bin/sh
# Gradle wrapper — uses system gradle if wrapper jar not present
if [ -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"
else
  exec gradle "$@"
fi
