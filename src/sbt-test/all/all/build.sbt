import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import com.sandinh.javamodule.moduleinfo.*
import com.sandinh.javamodule.moduleinfo.Utils.ModuleIDOps

inThisBuild(
  Seq(
    scalaVersion := "3.3.0",
    organization := "com.sandinh",
  )
)
lazy val sub = project.settings(
  moduleInfo / moduleName := "sd.test.all",
)
lazy val all = project
  .in(file("."))
  .dependsOn(sub)
  .enablePlugins(ModuleInfoPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.2",
      "org.jetbrains" % "annotations" % "24.0.1" % Provided,
    ),
    moduleInfo / moduleName := "sd.test.all",
    moduleInfos := Seq(
      KnownModule.of(sub).value,
      AutomaticModuleName(
        "com.thoughtworks.paranamer:paranamer",
        "paranamer"
      ),
      JModuleInfo(
        "com.fasterxml.jackson.module:jackson-module-scala_3",
        "com.fasterxml.jackson.scala",
      ),
    ),
    scriptedScalatestSpec := Some(new AnyFlatSpec with Matchers with ScriptedScalatestSuiteMixin {
      override val sbtState: State = state.value

      "ModuleInfoPlugin" should "generate module-info.class" in {
        val Some((_, Value(ret))) = Project.runTask(Compile / products, sbtState)
        val f = ret.find(_.name == "module-info.class")
        assert(f.isDefined)
        assert(f.get.getParentFile.name == "classes")
      }
    }),
  )
