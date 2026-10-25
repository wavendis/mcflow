import xyz.wagyourtail.jvmdg.gradle.task.files.DowngradeFiles

plugins {
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("xyz.wagyourtail.jvmdowngrader") version "1.3.6"
}

val modid: String by project
val mcVersion: String by project

// Minecraft 1.8.9 runs on Java 8, but Mc'Flow's code is shared with the modern versions and written in modern Java:
// it's compiled with Java 21 and then downgraded to Java 8 bytecode by JvmDowngrader (with stubs for the newer APIs
// it uses, e.g. List.of, shaded in). The dev client and the release jar both use the downgraded classes.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}
tasks.compileJava {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    options.release.set(21)
    options.encoding = "UTF-8"
}

loom {
    log4jConfigs.from(file("log4j2.xml"))
    runConfigs {
        "client" {
            // Trust the Windows cert store so LRCLIB works behind HTTPS-scanning antivirus (e.g. Avast)
            vmArg("-Djavax.net.ssl.trustStoreType=Windows-ROOT")
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }
}

// Forge 1.8.9 in dev expects the resources next to the classes
sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

repositories {
    maven("https://maven.wagyourtail.xyz/releases")
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    filesMatching(listOf("mcmod.info", "assets/mcflow/version.txt")) { // version.txt: for the update check
        expand(inputs.properties)
    }
}

// ---- the classes themselves are downgraded right after compiling, so the dev client (Java 8) can run them ----
val classesDir = sourceSets.main.get().java.classesDirectory
val downgradeClasses by tasks.registering(DowngradeFiles::class) {
    dependsOn(tasks.compileJava, tasks.processResources) // classes + resources share one folder here
    inputCollection = files(classesDir)
    classpath = sourceSets.main.get().compileClasspath
}
val replaceWithDowngraded by tasks.registering(Copy::class) {
    dependsOn(downgradeClasses)
    from(downgradeClasses.map { it.outputCollection })
    into(classesDir)
}
tasks.classes { dependsOn(replaceWithDowngraded) }

// stubs for the newer Java APIs (List.of...) on the dev classpath; the release jar has the ones it uses shaded in
dependencies {
    runtimeOnly("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:1.3.6:downgraded-8") { isTransitive = false }
}

// ---- release jar: jar -> downgrade -> shade API stubs -> remap to Forge's names ----
tasks.jar {
    archiveBaseName.set(modid)
    archiveClassifier.set("dev")
    from("../LICENSE") { rename { "LICENSE_mcflow" } }
}
tasks.downgradeJar {
    // the jar's classes are already Java 8; this only records what to shade
    inputFile.set(tasks.jar.flatMap { it.archiveFile })
    archiveClassifier.set("dev-downgraded")
}
tasks.shadeDowngradedApi {
    inputFile.set(tasks.downgradeJar.flatMap { it.archiveFile })
    archiveClassifier.set("dev-downgraded-shaded")
}
tasks.remapJar {
    input.set(tasks.shadeDowngradedApi.flatMap { it.archiveFile })
    archiveBaseName.set(modid)
    archiveClassifier.set("")
}

// ---- dev client ----
// Loom's own runClient task fails Gradle 8's task validation, so launch the same way it would: through Fabric's dev
// launch injector, with Loom's generated launch config, on Java 8.
val runMc by tasks.registering(JavaExec::class) {
    group = "minecraft"
    description = "Starts the 1.8.9 dev client with Mc'Flow"
    dependsOn(tasks.classes, "downloadAssets")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    mainClass.set("net.fabricmc.devlaunchinjector.Main")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = file("run")
    jvmArgs(
        "-Dfabric.dli.config=" + file(".gradle/loom-cache/launch.cfg").absolutePath,
        "-Dfabric.dli.env=client",
        "-Dfabric.dli.main=net.minecraft.launchwrapper.Launch",
        // trust the Windows cert store so LRCLIB works behind HTTPS-scanning antivirus (e.g. Avast)
        "-Djavax.net.ssl.trustStoreType=Windows-ROOT",
    )
    doFirst { workingDir.mkdirs() }
}
