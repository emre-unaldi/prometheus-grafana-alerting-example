#!/bin/sh
# Maven Wrapper Script
# This script allows you to use Maven without installing it globally

MAVEN_VERSION=3.9.6
MAVEN_WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES=".mvn/wrapper/maven-wrapper.properties"

# Create .mvn directory if it doesn't exist
mkdir -p .mvn/wrapper

# Download Maven wrapper jar if needed
if [ ! -f "$MAVEN_WRAPPER_JAR" ]; then
    echo "Downloading Maven wrapper..."
    curl -o "$MAVEN_WRAPPER_JAR" https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar
fi

# Execute Maven
exec java -jar "$MAVEN_WRAPPER_JAR" "$@"
