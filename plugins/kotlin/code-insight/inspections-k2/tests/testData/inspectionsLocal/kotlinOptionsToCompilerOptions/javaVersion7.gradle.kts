// FIX: Replace 'kotlinOptions' with 'compilerOptions'
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

val compileKotlin: KotlinCompile by tasks
compileKotlin.<caret>kotlinOptions {
    // Kotlin doesn't support jvmTarget = 1.7, we don't convert it properly intentionally
    jvmTarget = JavaVersion.VERSION_1_7.toString()
}
