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

val hostIsWindows = System.getProperty("os.name").lowercase().contains("windows")
val nethernetExecutable = if (hostIsWindows) "navro-nethernet.exe" else "navro-nethernet"
val nethernetOutput = layout.buildDirectory.file("nethernet/$nethernetExecutable")
val buildNetherNetFrontend by tasks.registering(Exec::class) {
    workingDir = layout.projectDirectory.dir("nethernet").asFile
    commandLine("go", "build", "-trimpath", "-o", nethernetOutput.get().asFile.absolutePath, "./inspect")
    inputs.files(fileTree("nethernet") { exclude("bin/**") })
    for (name in listOf("GOOS", "GOARCH", "CGO_ENABLED")) {
        inputs.property(name, providers.environmentVariable(name).orElse("auto"))
    }
    inputs.property("hostOs", System.getProperty("os.name"))
    inputs.property("hostArch", System.getProperty("os.arch"))
    outputs.file(nethernetOutput)
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
            from(nethernetOutput) {
                into("bin")
                filesMatching(nethernetExecutable) { permissions { unix("rwxr-xr-x") } }
            }
            from("src/main/resources") {
                include("config.yml", "messages.yml", "punishments.yml", "network.yml")
                into("plugins/Navro")
            }
        }
    }
}

for (name in listOf("distZip", "distTar", "installDist")) {
    tasks.named(name) { dependsOn(buildNetherNetFrontend) }
}
