plugins {
  id("java-gradle-plugin")
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
}

dependencies {
  implementation(kotlin("gradle-plugin-api"))
}

buildConfig {
  val project = project(":ceval-plugin")
  packageName(project.group.toString())
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.extra["kotlin_plugin_id"]}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${project.group}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${project.name}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${project.version}\"")
}

gradlePlugin {
  plugins {
    create("ceval") {
      id = rootProject.extra["kotlin_plugin_id"] as String
      displayName = "CEval"
      description = "CEval"
      implementationClass = "com.github.skifire13.ceval.CEvalGradlePlugin"
    }
  }
}
