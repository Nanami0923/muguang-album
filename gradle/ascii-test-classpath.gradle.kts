import java.io.File
import org.gradle.api.tasks.testing.Test

// JDK launchers on Windows read @argfiles using the native code page, even when
// the Gradle daemon writes them as UTF-8. Keep only JVM test launch paths ASCII;
// normal Android compilation, original sources and build outputs stay in place.
val needsAsciiTestPaths = System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
    rootProject.projectDir.absolutePath.any { it.code > 127 }

if (needsAsciiTestPaths) {
    val projectRootPath = rootProject.projectDir.absoluteFile.normalize().toPath()
    val projectKey = Integer.toUnsignedString(rootProject.projectDir.absolutePath.hashCode(), 16)
    val stagingRoot = File(gradle.gradleUserHomeDir, "muguang-ascii-tests/$projectKey").absoluteFile.normalize()
    require(stagingRoot.absolutePath.all { it.code <= 127 }) {
        "JVM test staging requires an ASCII Gradle user-home path. Set GRADLE_USER_HOME to an ASCII directory."
    }

    tasks.withType<Test>().configureEach {
        val testTask = this
        val taskKey = Integer.toUnsignedString(path.hashCode(), 16)
        doFirst {
            val originalClasspath = testTask.classpath.files.toList()
            val originalTestDirectories = testTask.testClassesDirs.files.toList()
            val replacements = mutableMapOf<File, File>()
            val candidates = (originalClasspath + originalTestDirectories).map { it.absoluteFile.normalize() }.distinct()

            candidates.forEachIndexed { index, source ->
                // Gradle's dependency cache already has ASCII paths on this machine.
                // Do not copy the entire dependency graph or modify cached artifacts.
                if (!source.toPath().startsWith(projectRootPath) || source.absolutePath.all { it.code <= 127 }) {
                    return@forEachIndexed
                }
                val destination = File(stagingRoot, "$taskKey/entry-$index").canonicalFile
                require(destination.toPath().startsWith(stagingRoot.canonicalFile.toPath())) {
                    "Test staging destination must remain inside the dedicated staging directory."
                }
                val stagedEntry = when {
                    source.isDirectory -> {
                        project.sync {
                            from(source)
                            into(destination)
                        }
                        destination
                    }
                    source.isFile -> {
                        // A fixed ASCII name also handles locally named JARs.
                        project.sync {
                            from(source)
                            into(destination)
                            rename { "content.jar" }
                        }
                        File(destination, "content.jar")
                    }
                    else -> {
                        // Java/AGP may list absent no-source output directories.
                        // Do not create them or accidentally load old staged classes.
                        File(destination, "absent")
                    }
                }
                replacements[source] = stagedEntry
            }

            fun remap(files: List<File>) = files.map { original ->
                replacements[original.absoluteFile.normalize()] ?: original
            }
            testTask.classpath = project.files(remap(originalClasspath))
            testTask.testClassesDirs = project.files(remap(originalTestDirectories))
            logger.info("Staged {} local JVM test entries under ASCII Gradle user-home paths.", replacements.size)
        }
    }
}
