plugins {
    application
}

group = "ac.navro"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
}

val userHome = System.getProperty("user.home")
val geyserRuntime = file(providers.environmentVariable("GEYSER_RUNTIME").getOrElse("$userHome/geyser-inspect/build/runtime"))
val cloudburstLatest = file(providers.environmentVariable("CLOUDBURST_RUNTIME").getOrElse("$userHome/cloudburst-latest"))

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    implementation(fileTree(geyserRuntime) {
        include("bedrock-connection-*.jar")
        include("bedrock-codec-*.jar")
        include("common-3.0.0.Beta*.jar")
        include("netty-transport-raknet-*.jar")
        include("netty-*.jar")
        include("*-common-*.jar")
        include("*-maps-*.jar")
        include("nbt-*.jar")
        include("immutable-*.jar")
        include("jose4j-*.jar")
        include("slf4j-api-*.jar")
        include("api-2.0.jar")
        include("*-sets-*.jar")
        include("core-8.5.15.jar")
        include("expiringmap-*.jar")
        include("guava-*.jar")
        include("failureaccess-*.jar")
        include("lmbda-*.jar")
        include("natives-*.jar")
        include("aircompressor-*.jar")
        include("gson-2*.jar")
        include("snakeyaml-2.2.jar")
        exclude("netty-codec-4.1*.jar")
        exclude("netty-transport-raknet-1.1*.jar")
        exclude("netty-transport-raknet-*.jar")
        exclude("netty-transport-native-*")
        exclude("bedrock-codec-*.jar")
        exclude("bedrock-connection-*.jar")
        exclude("common-3.0.0.Beta*.jar")
        exclude("nbt-*.jar")
    })
    implementation(files(
        file("$cloudburstLatest/bedrock-codec.jar"),
        file("$cloudburstLatest/bedrock-connection.jar"),
        file("$cloudburstLatest/common.jar"),
        file("$cloudburstLatest/nbt.jar"),
        file("$cloudburstLatest/netty-transport-raknet.jar"),
        file("$cloudburstLatest/netty-transport-common.jar")
    ))
}

application {
    mainClass = "ac.navro.proxy.NavroProxy"
}

sourceSets.test {
    java.srcDir("backend/geyser/src/main/java")
}

tasks.jar {
    archiveFileName.set("NavroProxy-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.test {
    useJUnitPlatform()
}
