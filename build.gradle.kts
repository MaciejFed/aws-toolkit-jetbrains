// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import toolkits.gradle.changelog.tasks.GenerateGithubChangeLog
import java.nio.file.Files
import java.nio.file.Paths

buildscript {
    repositories {
        maven { url "https://plugins.gradle.org/m2/" }
        mavenCentral()
        jcenter()
    }
    dependencies {
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"
        classpath "gradle.plugin.org.jetbrains.intellij.plugins:gradle-intellij-plugin:$ideaPluginVersion"
        classpath "com.adarshr:gradle-test-logger-plugin:1.7.0"
    }
}

plugins {
    id "de.undercouch.download" version "4.1.1" apply false
}

apply from: "intellijJVersions.gradle"

def ideVersion = shortenVersion(resolveIdeProfileName())

group "software.aws.toolkits"
// please check changelog generation logic if this format is changed
version "$toolkitVersion-$ideVersion".toString()

repositories {
    maven { url "https://www.jetbrains.com/intellij-repository/snapshots/" }
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        jcenter()
    }

    apply plugin: "com.adarshr.test-logger"
    apply plugin: "java"
    apply plugin: "jacoco"

    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    tasks.withType(JavaExec) {
        systemProperty("aws.toolkits.enableTelemetry", false)
    }

    tasks.withType(org.jetbrains.intellij.tasks.RunIdeTask) {
        intellij {
            if (System.env.ALTERNATIVE_IDE) {
                if (file(System.env.ALTERNATIVE_IDE).exists()) {
                    alternativeIdePath = System.env.ALTERNATIVE_IDE
                } else {
                    throw new GradleException("ALTERNATIVE_IDE path not found"
                        + (System.env.ALTERNATIVE_IDE ==~ /.*[\/\\] *$/
                        ? " (HINT: remove trailing slash "/")"
                        : ": ${System.env.ALTERNATIVE_IDE}"))
                }
            }
        }
    }

    configurations {
        runtimeClasspath.exclude group: "org.slf4j"
        runtimeClasspath.exclude group: "org.jetbrains.kotlin"
        runtimeClasspath.exclude group: "org.jetbrains.kotlinx"
        runtimeClasspath.exclude group: "software.amazon.awssdk", module: "netty-nio-client"
    }
}

// Kotlin plugin seems to be bugging out when there are no kotlin sources
configure(subprojects - project(":telemetry-client")) {
    apply plugin: "kotlin"

    sourceSets {
        integrationTest {
            kotlin.srcDir "it"
        }
    }
}

subprojects {
    group = parent.group
    version = parent.version

    apply plugin: "java"
    apply plugin: "idea"

    sourceSets {
        main.java.srcDirs = SourceUtils.findFolders(project, "src", ideVersion)
        main.resources.srcDirs = SourceUtils.findFolders(project, "resources", ideVersion)
        test.java.srcDirs = SourceUtils.findFolders(project, "tst", ideVersion)
        test.resources.srcDirs = SourceUtils.findFolders(project, "tst-resources", ideVersion)
        integrationTest {
            compileClasspath += main.output + test.output
            runtimeClasspath += main.output + test.output
            java.srcDirs = SourceUtils.findFolders(project, "it", ideVersion)
            resources.srcDirs = SourceUtils.findFolders(project, "it-resources", ideVersion)
        }
    }

    configurations {
        testArtifacts

        integrationTestImplementation.extendsFrom testImplementation
        integrationTestRuntimeOnly.extendsFrom testRuntimeOnly
    }

    dependencies {
        implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion"
        implementation "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion"
        testImplementation "com.nhaarman.mockitokotlin2:mockito-kotlin:$mockitoKotlinVersion"
        testImplementation "org.mockito:mockito-core:$mockitoVersion"
        testImplementation "org.assertj:assertj-core:$assertjVersion"
        testImplementation "junit:junit:$junitVersion"
    }

    testlogger {
        showFullStackTraces true
        showStandardStreams true
        showPassedStandardStreams false
        showSkippedStandardStreams true
        showFailedStandardStreams true
    }

    test {
        jacoco {
            // don"t instrument sdk, icons, ktlint, etc.
            includes = ["software.aws.toolkits.*"]
            excludes = ["software.aws.toolkits.ktlint.*"]
        }

        reports {
            junitXml.enabled = true
            html.enabled = true
        }
    }

    idea {
        module {
            sourceDirs += sourceSets.main.java.srcDirs
            resourceDirs += sourceSets.main.resources.srcDirs
            testSourceDirs += file("tst-$ideVersion")
            testResourceDirs += file("tst-resources-$ideVersion")

            sourceDirs -= file("it")
            testSourceDirs += file("it")
            testSourceDirs += file("it-$ideVersion")

            resourceDirs -= file("it-resources")
            testResourceDirs += file("it-resources")
            testResourceDirs += file("it-resources-$ideVersion")
        }
    }

    task integrationTest(type: Test) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs the integration tests."
        testClassesDirs = sourceSets.integrationTest.output.classesDirs
        classpath = sourceSets.integrationTest.runtimeClasspath

        jacoco {
            // don"t instrument sdk, icons, ktlint, etc.
            includes = ["software.aws.toolkits.*"]
            excludes = ["software.aws.toolkits.ktlint.*"]
        }

        project.plugins.withId("org.jetbrains.intellij") {
            systemProperty("log.dir", "${project.intellij.sandboxDirectory}-test/logs")
        }

        mustRunAfter tasks.test
    }

    project.plugins.withId("org.jetbrains.intellij") {
        downloadRobotServerPlugin.version = remoteRobotVersion

        tasks.withType(org.jetbrains.intellij.tasks.RunIdeForUiTestTask).all {
            systemProperty "robot-server.port", remoteRobotPort
            systemProperty "ide.mac.file.chooser.native", "false"
            systemProperty "jb.consents.confirmation.enabled", "false"
            // This does some magic in EndUserAgreement.java to make it not show the privacy policy
            systemProperty "jb.privacy.policy.text", "<!--999.999-->"
            if (System.getenv("CI") != null) {
                systemProperty("aws.sharedCredentialsFile", "/tmp/.aws/credentials")
            }
        }

        jacoco.applyTo(runIdeForUiTests)
    }

    tasks.withType(KotlinCompile).all {
        kotlinOptions.jvmTarget = "1.8"
    }

    // Force us to compile the integration tests even during check even though we don"t run them
    check.dependsOn(integrationTestClasses)

    task testJar(type: Jar) {
        baseName = "${project.name}-test"
        from sourceSets.test.output
        from sourceSets.integrationTest.output
    }

    artifacts {
        testArtifacts testJar
    }

    // Remove the tasks added in by gradle-intellij-plugin so that we don"t publish/verify multiple times
    project.afterEvaluate {
        removeTask(tasks, org.jetbrains.intellij.tasks.PublishTask)
        removeTask(tasks, org.jetbrains.intellij.tasks.VerifyPluginTask)
        removeTask(tasks, org.jetbrains.intellij.tasks.BuildSearchableOptionsTask)
    }
}

configurations {
    ktlint
}

def removeTask(TaskContainer tasks, Class<? extends Task> takeType) {
    tasks.withType(takeType).configureEach {
        setEnabled(false)
    }
}

apply plugin: "org.jetbrains.intellij"
apply plugin: "toolkit-change-log"

intellij {
    version ideSdkVersion("IC")
    pluginName "aws-jetbrains-toolkit"
    updateSinceUntilBuild false
    downloadSources = System.getenv("CI") == null
}

prepareSandbox {
    tasks.findByPath(":jetbrains-rider:prepareSandbox")?.collect {
        from(it)
    }
}

publishPlugin {
    token publishToken
    channels publishChannel ? publishChannel.split(",").collect { it.trim() } : []
}

tasks.register("generateChangeLog", GenerateGithubChangeLog) {
    changeLogFile = project.file("CHANGELOG.md")
}

task ktlint(type: JavaExec, group: "verification") {
    description = "Check Kotlin code style."
    classpath = configurations.ktlint
    main = "com.pinterest.ktlint.Main"

    def isWindows = System.properties["os.name"].toLowerCase().contains("windows")

    def toInclude = project.rootDir.relativePath(project.projectDir) + "/**/*.kt"
    def toExclude = project.rootDir.relativePath(new File(project.projectDir, "jetbrains-rider")) + "/**/*.Generated.kt"

    if (isWindows) {
        toInclude = toInclude.replace("/", "\\")
        toExclude = toExclude.replace("/", "\\")
    }

    args "-v", toInclude, "!${toExclude}", "!/**/generated-src/**/*.kt"

    inputs.files(project.fileTree(dir: ".", include: "**/*.kt"))
    outputs.dir("${project.buildDir}/reports/ktlint/")
}

task validateLocalizedMessages(group: "verification") {
    doLast {
        BufferedReader files = Files.newBufferedReader(Paths.get("${project.rootDir}/resources/resources/software/aws/toolkits/resources/localized_messages.properties"))
        files
            .lines()
            .map({ item ->
                if (item == null || item.isEmpty()) {
                    return ""
                }
                String[] chunks = item.split("=")
                if (chunks.length <= 1) {
                    return ""
                } else {
                    return chunks[0]
                }
            })
            .filter({ item -> !item.isEmpty() })
            .reduce({ item1, item2 ->
                if (item1 > item2) {
                    throw new GradleException("localization file is not sorted:" + item1 + " > " + item2)
                }

                return item2
            })
    }
}

check.dependsOn ktlint
check.dependsOn validateLocalizedMessages
check.dependsOn verifyPlugin

task coverageReport(type: JacocoReport) {
    executionData fileTree(project.rootDir.absolutePath).include("**/build/jacoco/*.exec")

    getAdditionalSourceDirs().from(subprojects.sourceSets.main.java.srcDirs)
    getSourceDirectories().from(subprojects.sourceSets.main.java.srcDirs)
    getClassDirectories().from(subprojects.sourceSets.main.output.classesDirs)

    reports {
        html.enabled true
        xml.enabled true
    }
}
subprojects.forEach {
    coverageReport.mustRunAfter(it.tasks.withType(Test))
}
check.dependsOn coverageReport

// Workaround for runIde being defined in multiple projects, if we request the root project runIde, "alias" it to
// community edition
if (gradle.startParameter.taskNames.contains("runIde")) {
    // Only disable this if running from root project
    if (gradle.startParameter.projectDir == project.rootProject.rootDir
        || System.properties.containsKey("idea.gui.tests.gradle.runner")) {
        println("Top level runIde selected, excluding sub-projects" runIde")
        gradle.taskGraph.whenReady { graph ->
            graph.allTasks.forEach {
                if (it.name == "runIde" &&
                    it.project != project(":jetbrains-core")) {
                    it.enabled = false
                }
            }
        }
    }
}

dependencies {
    implementation project(":jetbrains-ultimate")
    project.findProject(":jetbrains-rider")?.collect {
        implementation it
    }

    ktlint "com.pinterest:ktlint:$ktlintVersion"
    ktlint project(":ktlint-rules")
}