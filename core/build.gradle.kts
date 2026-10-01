plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
val protocolTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.readiz.wgtinstaller.core.CoreTestsKt")
    args(layout.buildDirectory.dir("fixtures").get().asFile.absolutePath)
}
tasks.check { dependsOn(protocolTest) }

dependencies { testImplementation(project(":simulator")) }

// Explicit opt-in: real public GitHub downloads, synthetic certificates, loopback TV only.
tasks.register<JavaExec>("releaseSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.readiz.wgtinstaller.core.ReleaseSmokeKt")
    args(layout.buildDirectory.file("reports/tizen-youtube-smoke.json").get().asFile.absolutePath)
}
