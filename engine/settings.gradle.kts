// Plugin dependencies come straight from Maven Central first: a plugin-portal outage once failed CI
// with "Could not find org.codehaus.plexus:plexus-utils" while the same artifacts were on Central.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "cassandra-studio-engine"
