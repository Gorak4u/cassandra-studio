plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.8"
    id("org.cyclonedx.bom") version "2.3.1"
}

group = "com.cassandrastudio"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

val driverVersion = "4.19.3"
val jacksonVersion = "2.22.3"

dependencies {
    // Security floor for transitive Netty (from the Cassandra driver): 4.1.130 has known CVEs
    // (incl. CVE-2026-75595, critical). Patch-level upgrade within the 4.1 line the driver uses.
    implementation(platform("io.netty:netty-bom:4.1.139.Final"))
    implementation("io.javalin:javalin:7.2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.apache.cassandra:java-driver-core:$driverVersion")
    implementation("org.apache.cassandra:java-driver-query-builder:$driverVersion")
    implementation("org.apache.sshd:sshd-core:2.20.0")
    // Ed25519 SSH keys (the usual modern key type): MINA SSHD needs this provider for them.
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    implementation("org.slf4j:slf4j-simple:2.0.20")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.testcontainers:cassandra:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

application {
    mainClass.set("com.cassandrastudio.engine.Main")
    // Same fix the control repo applies to nodetool: newer JDKs reject the
    // RMI URLs some Cassandra versions advertise.
    // Basic auth to a corporate proxy for HTTPS tunnels (update check) is off in the JDK by default (NFR-NET).
    applicationDefaultJvmArgs = listOf("-Dcom.sun.jndi.rmiURLParsing=legacy", "-Djdk.http.auth.tunneling.disabledSchemes=")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
}

tasks.test {
    useJUnitPlatform {
        // Integration tests start real Cassandra containers: -Pintegration.
        // Keychain tests need a real OS keychain (Windows, macOS): -Pkeychain.
        when {
            project.hasProperty("keychain") -> includeTags("keychain")
            project.hasProperty("integration") -> excludeTags("keychain")
            else -> excludeTags("integration", "keychain")
        }
    }
    systemProperty("cassandra.versions", project.findProperty("cassandraVersions") ?: "4.1")
    systemProperty("jdk.http.auth.tunneling.disabledSchemes", "") // as the app runs (see application above)
    maxHeapSize = "1g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.shadowJar {
    archiveBaseName.set("cassandra-studio-engine")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest { attributes["Main-Class"] = "com.cassandrastudio.engine.Main" }
}

val writeVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast { out.get().file("studio-version.txt").asFile.apply { parentFile.mkdirs(); writeText(v) } }
}
sourceSets.main { resources.srcDir(writeVersion) }
