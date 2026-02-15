plugins {
    kotlin("jvm") version "1.9.24"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "me.l1cked"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("org.incendo:cloud-paper:2.0.0-beta.10")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

tasks {
    runServer {
        minecraftVersion("1.21.1")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching(listOf("plugin.yml", "plugin-thin.yml")) {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("thin")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.get().output) {
            exclude("plugin.yml", "plugin-thin.yml")
        }
        from(sourceSets.main.get().output) {
            include("plugin-thin.yml")
            rename("plugin-thin.yml", "plugin.yml")
        }
        from("LICENSE") {
            into("META-INF")
        }
        from("NOTICE") {
            into("META-INF")
        }
    }

    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("org.incendo", "me.l1cked.contractscrolls.lib.incendo")
        exclude("plugin-thin.yml")
        from("LICENSE") {
            into("META-INF")
        }
        from("NOTICE") {
            into("META-INF")
        }
    }
}
