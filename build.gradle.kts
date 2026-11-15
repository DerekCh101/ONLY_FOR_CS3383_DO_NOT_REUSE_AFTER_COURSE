import org.gradle.jvm.tasks.Jar
import java.util.LinkedHashSet
import java.util.zip.ZipFile

plugins {
    id("application")
    id("jacoco")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.ashoreprospect"
// Mirrors AppVersion.LABEL: the UI version and the release tag must not drift apart.
version = "v0.1.7"

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
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web")
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Coverage gate for the headless-testable core.
//
// The JavaFX views, the HTTP client, and the Postgres repository all need a display, a
// live server, or Docker, so they are simply not part of the gate (see TEST_PLAN.md).
// Every class named below must keep at least 60% LINE coverage; the rules are per class,
// because the Gradle plugin in use ignores `excludes` and a "pkg.*" pattern would also
// match nested packages.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        // Exact class lists: a "pkg.*" pattern would also match nested packages.
        listOf(
            "org.ashoreprospect.AppTimeReference",
            "org.ashoreprospect.BlockedTime",
            "org.ashoreprospect.BlockedTimePeriod",
            "org.ashoreprospect.Day",
            "org.ashoreprospect.RefreshLifeCycle",
            "org.ashoreprospect.SchedulerEngine",
            "org.ashoreprospect.Task",
            "org.ashoreprospect.TaskLifeCycle",
            "org.ashoreprospect.TaskManagementSystem",
            "org.ashoreprospect.ValidateAddTaskInput",
            "org.ashoreprospect.Workflow",
            "org.ashoreprospect.Command.AddTask",
            "org.ashoreprospect.Command.AddTaskWithFile",
            "org.ashoreprospect.Command.ClearAllTask",
            "org.ashoreprospect.Command.ClearPastTasks",
            "org.ashoreprospect.Command.ClearTask",
            "org.ashoreprospect.Command.EditTask",
            "org.ashoreprospect.Command.FinishTask",
            "org.ashoreprospect.Command.InAppCli",
            "org.ashoreprospect.Command.MoveTask",
            "org.ashoreprospect.Command.PrintAllTask",
            "org.ashoreprospect.Command.RecordedCommand",
            "org.ashoreprospect.Command.Redo",
            "org.ashoreprospect.Command.RunSchedulerCommand",
            "org.ashoreprospect.Command.SystemCommands",
            "org.ashoreprospect.Command.TaskLookup",
            "org.ashoreprospect.Command.Undo",
            "org.ashoreprospect.Command.UnfinishTask",
            "org.ashoreprospect.crypto.Vault",
            "org.ashoreprospect.GUI.theme.Contrast",
            "org.ashoreprospect.GUI.theme.ThemePalette"
        ).forEach { className ->
            rule {
                element = "CLASS"
                includes = listOf(className)
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.60".toBigDecimal()
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

application {
    mainClass = "org.ashoreprospect.SchedulerApp"
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
        attributes["Main-Class"] = "org.ashoreprospect.CliRunner.CLIRunner"
    }
    from(sourceSets.main.get().output)
}

tasks.named("build") {
    dependsOn("cliJar")
}