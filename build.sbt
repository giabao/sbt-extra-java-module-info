inThisBuild(
  Seq(
    organization := "com.sandinh",
    versionScheme := Some("early-semver"),
    developers := List(
      Developer(
        "thanhbv",
        "Bui Viet Thanh",
        "thanhbv@sandinh.net",
        url("https://sandinh.com")
      )
    ),
    licenses := Seq("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scalaVersion := "2.12.18",
  )
)

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-java-module-info",
    pluginCrossBuild / sbtVersion := "1.9.0",
    libraryDependencies ++= Seq(
      "org.jetbrains" % "annotations" % "24.0.1" % Provided,
      "org.ow2.asm" % "asm-tree" % "9.5",
    ),
    scriptedScalatestDependencies ++= Seq(
      "org.scalatest::scalatest-flatspec:3.2.16",
      "org.scalatest::scalatest-mustmatchers:3.2.16",
    ),
  )
