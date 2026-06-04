plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.jsoup:jsoup:1.22.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
