plugins {
    application
}

group = "ac.navro"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val protocolVersion = "3.0.0.Beta15-20261116.161745-6"
val raknetVersion = "1.1.0.CR4-20261103.123906-3"

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.cloudburstmc.protocol") useVersion(protocolVersion)
        if (requested.group == "org.cloudburstmc.netty") useVersion(raknetVersion)
    }
}

dependencies {
    implementation(platform("io.netty:netty-bom:4.2.7.Final"))
    implementation("org.cloudburstmc.protocol:bedrock-connection:$protocolVersion")
    implementation("org.cloudburstmc.netty:netty-transport-raknet:$raknetVersion")
    implementation("org.cloudburstmc.math:immutable:2.0")
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.google.guava:guava:31.0.1-jre")
    implementation("org.slf4j:slf4j-api:2.0.9")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

application {
    mainClass = "ac.navro.proxy.NavroProxy"
}

sourceSets.test {
    java.srcDir("backend/geyser/src/main/java")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("NavroProxy-${project.version}.jar")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    maxParallelForks = 1
    forkEvery = 10
}

distributions {
    main {
        contents {
            from("config.example.yml") { rename { "config.yml" } }
            from("README.md")
            from("DESKTOP_BUILD_VALIDATION.md")
        }
    }
}
