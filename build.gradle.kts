// Top-level build file – Gradle 8.6 + AGP 8.2.2 compatible
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

// Replaces deprecated Project.task() and buildDir with tasks.register() and layout.buildDirectory
tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
