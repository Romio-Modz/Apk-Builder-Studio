#!/bin/sh

#
# Gradle start up script
#

DIRNAME="$(dirname "$0")"
APP_HOME="$(cd "$DIRNAME" && pwd)"

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
        exit 1
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || {
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
        exit 1
    }
fi

# Use the maximum available, or set MAX_FD != -1 to use that value
MAX_FD="maximum"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    -Dorg.gradle.appname="$(basename "$0")" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
