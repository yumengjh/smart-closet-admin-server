#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES")
DIST_SHA512=$(sed -n 's/^distributionSha512Sum=//p' "$PROPERTIES")
DIST_NAME=${DIST_URL##*/}
MAVEN_DIR=${DIST_NAME%-bin.zip}
WRAPPER_HOME=${MAVEN_USER_HOME:-"$HOME/.m2"}/wrapper/dists/$MAVEN_DIR

if [ ! -x "$WRAPPER_HOME/bin/mvn" ]; then
    mkdir -p "$WRAPPER_HOME.tmp"
    ARCHIVE="$WRAPPER_HOME.tmp/$DIST_NAME"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --silent --show-error "$DIST_URL" --output "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget --quiet "$DIST_URL" --output-document="$ARCHIVE"
    else
        echo "Error: curl or wget is required to install Maven." >&2
        exit 1
    fi
    if command -v sha512sum >/dev/null 2>&1; then
        ACTUAL_SHA512=$(sha512sum "$ARCHIVE" | awk '{print $1}')
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL_SHA512=$(shasum -a 512 "$ARCHIVE" | awk '{print $1}')
    else
        echo "Error: sha512sum or shasum is required to verify Maven." >&2
        exit 1
    fi
    if [ "$ACTUAL_SHA512" != "$DIST_SHA512" ]; then
        echo "Error: Maven distribution checksum verification failed." >&2
        exit 1
    fi
    unzip -q "$ARCHIVE" -d "$WRAPPER_HOME.tmp"
    rm -f "$ARCHIVE"
    rm -rf "$WRAPPER_HOME"
    mv "$WRAPPER_HOME.tmp/$MAVEN_DIR" "$WRAPPER_HOME"
    rmdir "$WRAPPER_HOME.tmp"
fi

exec "$WRAPPER_HOME/bin/mvn" -f "$BASE_DIR/pom.xml" "$@"
