plugins { id("org.jetbrains.kotlin.jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.formdev:flatlaf:3.6")
    implementation("net.java.dev.jna:jna-platform:5.16.0")
    testImplementation(kotlin("test"))
    testImplementation("org.nanohttpd:nanohttpd:2.3.1")
}
application {
    mainClass.set("dev.aigw.windows.MainKt")
    applicationName = "ocean-model-harbor"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
tasks.test { useJUnitPlatform() }
