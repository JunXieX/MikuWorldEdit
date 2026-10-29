import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    id("buildlogic.adapter")
}

paperweight {
    // Only the Mojang-mapped variant is produced: FastAsyncWorldEdit ships a single Paper-targeted JAR.
    reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks
    .withType<JavaCompile>()
    .matching { it.name == "compileJava" || it.name == "compileTestJava" }
    .configureEach {
        // The pre-26 adapters are written against Java 21.
        options.release.set(21)
    }
