import androidx.media3.buildlogic.Media3Modules

pluginManagement {
  includeBuild("build-logic-settings")
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("gradlebuild.media3-settings-logic") }

rootProject.name = "androidx.media3"

val requiredModules =
  setOf(
    "lib-common",
    "lib-container",
    "lib-database",
    "lib-datasource",
    "lib-datasource-okhttp",
    "lib-datasource-rtmp",
    "lib-decoder",
    "lib-exoplayer",
    "lib-exoplayer-dash",
    "lib-exoplayer-hls",
    "lib-exoplayer-rtsp",
    "lib-exoplayer-smoothstreaming",
    "lib-extractor",
    "lib-session",
    "lib-ui",
    "lib-ui-danmaku",
  )

Media3Modules.EXTERNAL_MODULES.forEach { (gradleName, moduleInfo) ->
  if (gradleName in requiredModules) {
    include(":$gradleName")
    project(":$gradleName").projectDir = file(moduleInfo.directory)
  }
}

val stubRoot = rootDir.parentFile.resolve(".github/media3-stubs")
setOf("lib-inspector", "test-data", "test-utils", "test-utils-robolectric").forEach { gradleName ->
  include(":$gradleName")
  project(":$gradleName").projectDir = stubRoot.resolve(gradleName)
}
