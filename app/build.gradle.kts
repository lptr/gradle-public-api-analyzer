plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:32.1.3-jre")
    implementation("com.ibm.wala:com.ibm.wala.core:1.6.4")
    implementation("info.picocli:picocli:4.6.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "org.gradle.research.PublicApiAnalyzer"
}
