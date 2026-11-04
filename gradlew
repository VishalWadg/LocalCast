#!/bin/sh
#
# Self-bootstrapping Gradle startup script for POSIX (Linux / macOS / GitHub Actions)
# Automatically uses gradle-wrapper.jar if present, or downloads Gradle 9.3.1 from
# gradle-wrapper.properties if gradle-wrapper.jar is not yet initialized.
#
set -e

APP_HOME=$( cd "${0%/*}" && pwd -P )
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if [ -f "$WRAPPER_JAR" ]; then
    exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

GRADLE_VERSION="9.3.1"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
if [ -f "$WRAPPER_PROPS" ]; then
    PARSED_URL=$(grep '^distributionUrl=' "$WRAPPER_PROPS" | cut -d'=' -f2- | sed 's/\\:/:/g' | tr -d '\r')
    if [ -n "$PARSED_URL" ]; then
        DIST_URL="$PARSED_URL"
    fi
fi

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin/localcast"
GRADLE_BIN="$DIST_DIR/gradle-${GRADLE_VERSION}/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DIST_DIR"
    ZIP_FILE="$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip"
    echo "Bootstrapping Gradle ${GRADLE_VERSION} from ${DIST_URL} ..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$DIST_URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$DIST_URL" -O "$ZIP_FILE"
    else
        echo "Error: Neither curl nor wget is installed to download Gradle." >&2
        exit 1
    fi
    unzip -q -o "$ZIP_FILE" -d "$DIST_DIR"
    rm -f "$ZIP_FILE"
    chmod +x "$GRADLE_BIN"
fi

exec "$GRADLE_BIN" "$@"
