plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")) }
tasks.register<JavaExec>("runDemo") {
    dependsOn(tasks.classes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.readiz.wgtinstaller.simulator.DemoMainKt")
}
