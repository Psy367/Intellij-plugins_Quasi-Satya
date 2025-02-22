// PROBLEM: Deprecated 'kotlinOptions' DSL should be replaced with 'compilerOptions'
// FIX: Replace 'kotlinOptions' with 'compilerOptions'
plugins {
    kotlin("jvm") version "2.0.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(8)
}

tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    <caret>kotlinOptions.freeCompilerArgs += "-Xexport-kdoc"
}
