import org.gradle.jvm.tasks.Jar
import java.util.LinkedHashSet
import java.util.zip.ZipFile

plugins {
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.example"
version = "v0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = "23"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
}

application {
    mainClass = "org.example.SchedulerApp"
}

val runtimeClasspath = configurations.runtimeClasspath

fun Jar.embedRuntimeDependencies() {
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    val mergedServices = layout.buildDirectory.dir("merged-services")
    from(runtimeClasspath.get().map { file ->
        if (file.isDirectory) {
            file
        } else {
            zipTree(file).matching {
                exclude("META-INF/services/**")
            }
        }
    })
    from(mergedServices)
    doFirst {
        val serviceEntries = mutableMapOf<String, LinkedHashSet<String>>()
        runtimeClasspath.get().filter { it.isFile }.forEach { file ->
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("META-INF/services/") && !it.isDirectory }
                    .forEach { entry ->
                        val providers = serviceEntries.getOrPut(entry.name) { LinkedHashSet() }
                        zip.getInputStream(entry).bufferedReader().useLines { lines ->
                            lines.map(String::trim)
                                .filter { it.isNotEmpty() && !it.startsWith("#") }
                                .forEach(providers::add)
                        }
                    }
            }
        }
        val outputDirectory = mergedServices.get().asFile
        outputDirectory.deleteRecursively()
        serviceEntries.forEach { (name, providers) ->
            val outputFile = outputDirectory.resolve(name)
            outputFile.parentFile.mkdirs()
            outputFile.writeText(providers.joinToString(System.lineSeparator()) + System.lineSeparator())
        }
    }
}

tasks.jar {
    archiveBaseName = "scheduler-gui"
    embedRuntimeDependencies()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.register<Jar>("cliJar") {
    archiveBaseName = "scheduler-cli"
    embedRuntimeDependencies()
    manifest {
        attributes["Main-Class"] = "org.example.CliRunner.CLIRunner"
    }
    from(sourceSets.main.get().output)
}