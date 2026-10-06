import java.io.File
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

// Documents is managed by File Provider/iCloud on this Mac. Keep the opt-in
// build output outside the source tree so generated DEX files are not synced.
val localPropertiesFile = layout.projectDirectory.file("local.properties").asFile
val localProperties = Properties()
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val configuredBuildRoot = localProperties.getProperty("fiilda.buildRoot")
if (configuredBuildRoot != null) {
    val buildRootValue = configuredBuildRoot.trim()
    if (buildRootValue.isEmpty()) {
        throw GradleException("fiilda.buildRoot must be an absolute path when specified")
    }

    val sourceRoot = rootProject.projectDir.canonicalFile
    val buildRoot = File(buildRootValue)
    if (!buildRoot.isAbsolute) {
        throw GradleException("fiilda.buildRoot must be an absolute path: $buildRootValue")
    }

    val canonicalBuildRoot = buildRoot.canonicalFile
    val sourcePath = sourceRoot.toPath()
    val buildRootPath = canonicalBuildRoot.toPath()
    if (sourcePath.startsWith(buildRootPath) || buildRootPath.startsWith(sourcePath)) {
        throw GradleException(
            "fiilda.buildRoot must not overlap the project directory: $canonicalBuildRoot",
        )
    }

    val assignedDirectories = mutableSetOf<File>()
    fun outputDirectoryFor(projectPath: String): File {
        val pathSegments = projectPath
            .removePrefix(":")
            .split(":")
            .filter(String::isNotEmpty)
        val relativePath = if (pathSegments.isEmpty()) {
            "root"
        } else {
            pathSegments.joinToString(File.separator)
        }
        val outputDirectory = canonicalBuildRoot.resolve(relativePath).canonicalFile
        if (!outputDirectory.toPath().startsWith(buildRootPath)) {
            throw GradleException(
                "fiilda.buildRoot produced an output directory outside itself: $outputDirectory",
            )
        }
        if (!assignedDirectories.add(outputDirectory)) {
            throw GradleException("Duplicate external build directory: $outputDirectory")
        }
        return outputDirectory
    }

    fun configureBuildDirectory(targetProject: Project) {
        targetProject.layout.buildDirectory.set(outputDirectoryFor(targetProject.path))
    }

    configureBuildDirectory(rootProject)
    subprojects { configureBuildDirectory(this) }
}
