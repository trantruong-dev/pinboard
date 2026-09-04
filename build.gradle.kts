import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.2.20"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog") version "2.4.0"
}

group = "dev.pinboard"
version = "0.1.0-SNAPSHOT"

dependencies {
  intellijPlatform {
    intellijIdeaCommunity("2025.2")
    bundledPlugin("com.intellij.mcpServer")
    bundledPlugin("Git4Idea")
    testFramework(TestFrameworkType.Platform)
  }
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  testImplementation("junit:junit:4.13.2")
}

kotlin {
  jvmToolchain(21)
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
      freeCompilerArgs.add("-Xjvm-default=all")
    }
  }
  withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
  }
}

intellijPlatform {
  pluginConfiguration {
    id = "dev.pinboard.agent"
    name = "Pinboard"
    description = """
      Pin feedback onto several pieces of code, then tell your agent to work through the lot.
      Adds an asynchronous feedback queue on top of the MCP server your JetBrains IDE already ships.
      <br/><br/>
      The built-in MCP server hands an agent your current selection: synchronous, one-shot. Reviewing
      code is not like that. You read a file, spot five things, and want to note all five, keep
      reading, and hand the batch over when you are done.
      <br/><br/>
      <ul>
        <li><b>A queue</b> - pin as many notes as you like, nothing is sent yet.</li>
        <li><b>Batching</b> - the agent picks up a cluster in one call instead of a round trip each.</li>
        <li><b>Threads</b> - the agent replies, asks questions, and records what it did.</li>
        <li><b>Survives a restart</b> - the queue and its history are still there tomorrow.</li>
        <li><b>Stale detection</b> - if the code moved after you pinned it, the agent is told, and is
            given the original snapshot and enclosing symbol to relocate from.</li>
      </ul>
      No MCP configuration to write by hand. No network calls, no telemetry.
    """.trimIndent()
    version = project.version.toString()
    vendor {
      name = "Pinboard"
    }
    ideaVersion {
      // No untilBuild on purpose: pinning it locks the plugin out every time an IDE ships a new
      // major version, for no benefit.
      sinceBuild = "252"
    }
    // Release notes come from CHANGELOG.md so the Marketplace page and the repository can never
    // drift apart. Written by the changelog plugin, never by hand.
    changeNotes = provider {
      with(changelog) {
        renderItem(
          (getOrNull(project.version.toString()) ?: getUnreleased())
            .withHeader(false)
            .withEmptySections(false),
          org.jetbrains.changelog.Changelog.OutputType.HTML,
        )
      }
    }
  }
  publishing {
    // Never a literal here: the token is a GitHub Actions secret, injected as an environment
    // variable at release time.
    token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
  }

  pluginVerification {
    ides {
      // CI drives one IDE per matrix job with -PverifyIde=IC-2025.2 and friends, so a
      // compatibility break names the IDE it broke on. Locally, with no property set, verify
      // against what is at hand.
      val requested = providers.gradleProperty("verifyIde").orNull
      if (requested.isNullOrBlank()) {
        current()
        latest()
      } else {
        // Notation is "<type>-<version>", e.g. IC-2025.2 or PY-2025.2, matching how the verifier
        // and the CI matrix name IDEs.
        val type = requested.substringBefore('-')
        val version = requested.substringAfter('-')
        create(type, version)
      }
    }
  }
}

changelog {
  version = project.version.toString()
  path = file("CHANGELOG.md").canonicalPath
  header = provider { "[${version.get()}] - ${org.jetbrains.changelog.date()}" }
  groups = listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")
  repositoryUrl = "https://github.com/trantruong-dev/pinboard"
}
