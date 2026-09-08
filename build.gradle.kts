import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.2.20"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog") version "2.4.0"
}

group = "dev.pinboard"
version = providers.gradleProperty("pluginVersion").get()

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
      <br/><br/>
      <a href="https://github.com/trantruong-dev/pinboard">Documentation and source</a> &middot;
      Free, and always will be. If it saves you time, you can
      <a href="https://buymeacoffee.com/trantruong.dev">buy me a coffee</a>.
    """.trimIndent()
    version = project.version.toString()
    vendor {
      name = "Trần Quang Trường"
      url = "https://github.com/trantruong-dev/pinboard"
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

// Rewrites the pluginVersion line in gradle.properties, and nothing else in the file - line endings,
// comments and the order of the other keys survive untouched, so the diff of a release is one line.
//
// This has to be its own Gradle invocation. project.version is read when the build is configured, so
// patchChangelog, buildPlugin and publishPlugin in the same invocation would all still be looking at
// the old number. `make release` runs them afterwards for that reason.
tasks.register("setVersion") {
  group = "release"
  description = "Writes pluginVersion in gradle.properties. Needs -PnewVersion=x.y.z."

  val requested = providers.gradleProperty("newVersion")
  val current = project.version.toString()
  val propertiesFile = layout.projectDirectory.file("gradle.properties").asFile

  doLast {
    val next = requested.orNull.orEmpty()
    require(Regex("""\d+\.\d+\.\d+""").matches(next)) {
      "setVersion needs -PnewVersion=x.y.z, got \"$next\""
    }
    require(next != current) {
      "The version is already $current. Releasing it again would overwrite the Marketplace build."
    }
    val text = propertiesFile.readText()
    val line = Regex("(?m)^pluginVersion=.*$").find(text)
      ?: error("No pluginVersion line in gradle.properties to rewrite.")
    propertiesFile.writeText(text.replaceRange(line.range, "pluginVersion=$next"))
    logger.lifecycle("Version $current -> $next")
  }
}

// The Unreleased section is the release notes: patchChangelog turns it into the version section, and
// that section is what the Marketplace shows as what is new. An empty one is not an error to the
// changelog plugin - it writes no section at all and says nothing about it, so the release goes out
// with nothing to show for itself. Caught here, before anything has been written or uploaded.
//
// Read out of the file rather than through the changelog plugin's model, so that what fails the
// check is exactly what a person sees in CHANGELOG.md.
tasks.register("checkReleaseNotes") {
  group = "release"
  description = "Fails if CHANGELOG.md has no entries under Unreleased."

  val changelogFile = layout.projectDirectory.file("CHANGELOG.md").asFile

  doLast {
    val heading = "## [Unreleased]"
    val text = changelogFile.readText()
    val start = text.indexOf(heading)
    require(start >= 0) { "CHANGELOG.md has no $heading section." }

    val rest = text.substring(start + heading.length)
    val end = rest.indexOf("\n## ")
    val body = if (end >= 0) rest.substring(0, end) else rest

    // Group headings are always there, empty or not, so only a line that is neither blank nor a
    // heading counts as something to release.
    val entries = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    require(entries.any()) {
      "CHANGELOG.md has nothing under $heading. Write what changed before releasing - that section " +
        "becomes the release notes on the Marketplace."
    }
  }
}

// The same section again, once patchChangelog has turned it into a version heading, written out for
// `gh release create --notes-file`. The GitHub release and the Marketplace description then say the
// same thing, because both are the one section a person wrote in CHANGELOG.md.
//
// A task rather than shell in the Makefile: that recipe has to read the same under cmd.exe and sh,
// and pulling a section out of a file is exactly the kind of text handling the two disagree about.
tasks.register("writeReleaseNotes") {
  group = "release"
  description = "Writes the current version's CHANGELOG.md section to build/release-notes.md."

  val changelogFile = layout.projectDirectory.file("CHANGELOG.md").asFile
  val notesFile = layout.buildDirectory.file("release-notes.md").get().asFile
  // Read at configuration time: the configuration cache forbids reaching for the project at
  // execution time, and the version cannot change inside one invocation anyway.
  val releaseVersion = project.version.toString()

  doLast {
    val heading = "## [$releaseVersion]"
    val text = changelogFile.readText()
    val start = text.indexOf(heading)
    require(start >= 0) {
      "CHANGELOG.md has no $heading section - roll Unreleased into it with patchChangelog first."
    }

    // From the end of the heading line to the next version heading, so the date on the heading and
    // the compare link below the last section are both left out.
    val rest = text.substring(text.indexOf('\n', start) + 1)
    val end = rest.indexOf("\n## ")
    val body = (if (end >= 0) rest.substring(0, end) else rest).trim()
    require(body.isNotEmpty()) { "The $heading section is empty - there is nothing to describe." }

    notesFile.parentFile.mkdirs()
    notesFile.writeText(body + "\n")
    logger.lifecycle("Release notes for $releaseVersion -> ${notesFile.path}")
  }
}

changelog {
  version = project.version.toString()
  path = file("CHANGELOG.md").canonicalPath
  // No brackets around the version: repositoryUrl makes the plugin bracket it itself to build the
  // compare link, and bracketing it here too renders the heading as [[0.0.1]].
  header = provider { "${version.get()} - ${org.jetbrains.changelog.date()}" }
  groups = listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")
  repositoryUrl = "https://github.com/trantruong-dev/pinboard"
}
