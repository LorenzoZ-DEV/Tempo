plugins {
    id("java-library")
    id("xyz.jpenilla.run-velocity") version "3.1.0"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    // HikariCP + driver MariaDB (compatibile anche con MySQL): inclusi nello shadowJar
    implementation("com.zaxxer:HikariCP:5.1.0") {
        exclude(group = "org.slf4j") // slf4j e' gia' fornito da Velocity
    }
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("com.zaxxer.hikari", "dev.lorenzzz.tempo.libs.hikari")
    }

    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion("3.5.0-SNAPSHOT")
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }
}
