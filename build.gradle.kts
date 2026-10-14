plugins { id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false }
allprojects { group = "dev.aigw"; version = "1.0.12" }
subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("-Dfile.encoding=UTF-8")
        // JDK 17's Windows launcher reads @argfiles in the system codepage, while
        // Gradle writes UTF-8. Stage classpath entries outside non-ASCII project paths.
        doFirst {
            if (System.getProperty("os.name").startsWith("Windows")) {
                val token = rootProject.projectDir.absolutePath.hashCode().toUInt().toString(16)
                val staging = File(System.getenv("TEMP"), "aigw-tests-$token-${project.name}")
                val staged = classpath.files.filter { it.exists() }.mapIndexed { index, source ->
                    val target = File(staging, "$index-${source.name}")
                    if (source.isDirectory) {
                        project.sync { from(source); into(target) }
                    } else {
                        target.parentFile.mkdirs()
                        source.copyTo(target, overwrite = true)
                    }
                    target
                }
                classpath = files(staged)
            }
        }
    }
}
