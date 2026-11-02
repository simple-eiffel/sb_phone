plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":fold"))
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    // JSON: the timeline sidecars, the vault manifests, and Cliopatria's 186 MB GeoJSON (read as a stream).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("sbphone.datapack.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
