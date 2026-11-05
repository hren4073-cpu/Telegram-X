plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(25) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit(); testLogging { events("passed", "failed", "skipped") } }
