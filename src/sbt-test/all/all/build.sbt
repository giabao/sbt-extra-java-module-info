import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import com.sandinh.javamodule.moduleinfo.*

lazy val all = project
  .in(file("."))
  .enablePlugins(ModuleInfoPlugin)
  .settings(
    scalaVersion := "3.3.0",
    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.2",
      "org.jetbrains" % "annotations" % "24.0.1" % Provided,
    ),
    moduleInfos := Seq(
      AutomaticModuleName(
        "com.thoughtworks.paranamer:paranamer",
        "paranamer"
      ),
      JModuleInfo(
        "com.fasterxml.jackson.module:jackson-module-scala_3",
        "com.fasterxml.jackson.scala",
        requireAllDefinedDependencies = true,
        exportAllPackages = true,
      ),
    ),
    scriptedScalatestSpec := Some(new AnyFlatSpec with Matchers with ScriptedScalatestSuiteMixin {
      override val sbtState: State = state.value

      "foo" should "bar" in {
        "hello" mustBe "hello"
      }
    }),
  )
