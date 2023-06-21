package com.sandinh.javamodule.moduleinfo

import sbt.*
import sbt.Keys.*
import Utils.*
import ExtraJavaModuleInfoTransform.{addAutomaticModuleName, addModuleDescriptor}
import sbt.Package.ManifestAttributes
import sbt.internal.BuildDependencies
import sbt.librarymanagement.Configurations.RuntimeInternal
import sbt.librarymanagement.ScalaModuleInfo
import sbt.sandinh.DependencyTreeAccess
import sbt.sandinh.DependencyTreeAccess.{moduleInfoDepGraph, toDepsMap}

object ModuleInfoPlugin extends AutoPlugin {
  object autoImport {
    val moduleInfo = settingKey[ModuleSpec](
      "JpmsModule or AutomaticModule used to generate module-info.class or set Automatic-Module-Name field in MANIFEST.MF for this project"
    )
    val moduleInfos = settingKey[Seq[ModuleSpec]](
      "extra Java module-info to patch non-module jar dependencies"
    )
    val moduleInfoFailOnMissing = settingKey[Boolean](
      "Fail update task if exist non-module jar dependencies and no ModuleSpec is defined for it"
    )
  }
  import autoImport.*

  override def globalSettings: Seq[Def.Setting[?]] = Seq(
    moduleInfos := Nil,
    moduleInfoFailOnMissing := false,
  )

  override def projectSettings: Seq[Setting[?]] = DependencyTreeAccess.settings ++ Seq(
    Compile / packageBin / packageOptions ++= (moduleInfo.value match {
      case AutomaticModule(moduleName, _, _) =>
        ManifestAttributes("Automatic-Module-Name" -> moduleName) +: Nil
      case _ => Nil
    }),
    Compile / products ++= moduleInfoGenClass.value,
    update := transformUpdateReport.value(update.value),
  )

  /** moduleInfo of inter-project dependencies */
  private def dependentProjectModules = Def.settingDyn[Seq[ModuleSpec]] {
    val deps = dependentProjects(buildDependencies.value, thisProjectRef.value)
    (deps.map(_ / moduleInfo).join zip
      deps.map(_ / projectID).join zip
      deps.map(_ / scalaModuleInfo).join) { case ((ms, ids), iss) =>
      (ms zip ids zip iss).map { case ((m, id), is) =>
        m.withDefaultId(id.jmodId(is))
      }
    }
  }

  /** All inter-project that `ref` dependsOn, transitively */
  private def dependentProjects(buildDeps: BuildDependencies, ref: ProjectRef): Seq[ProjectRef] = {
    val refs = buildDeps.classpath(ref).collect {
      case d if isRuntimeDepend(d.configuration) => d.project
    }
    (refs ++ refs.flatMap(dependentProjects(buildDeps, _)) // also find transitive deps
    ).distinct
  }

  private def transformUpdateReport = Def.task[UpdateReport => UpdateReport] {
    val log = streams.value.log
    val infos = moduleInfos.value ++ dependentProjectModules.value
    val out = (ThisBuild / baseDirectory).value / "target/moduleInfo"
    out.mkdirs()
    val failOnMissing = moduleInfoFailOnMissing.value
    val compileDepsMap = toDepsMap((Compile / moduleInfoDepGraph).value)
    val runtimeDepsMap = toDepsMap((Runtime / moduleInfoDepGraph).value)
    val clsTypes = classpathTypes.value

    (report: UpdateReport) => {
      val args = new ModuleInfoArgs(infos, clsTypes, report)
      val remapped = report
        .configuration(RuntimeInternal)
        .get
        .modules
        .filter(_.artifacts.nonEmpty)
        .map { mod =>
          val id = mod.module.jmodId
          if (infos.exists(_.mergedJars.contains(id))) id -> Nil
          else {
            val originalJar = jarOf(mod)
            val moduleJar = out / originalJar.name
            val remappedJar = infos.find(_.id == id) match {
              case Some(_: KnownModule) => originalJar
              case Some(info: JpmsModule) =>
                if (originalJar.jpmsModuleName.isDefined)
                  log.warn(s"Already be a jpms module $id -> $originalJar")
                genIfNotExist(
                  moduleJar,
                  addModuleDescriptor(args, compileDepsMap, runtimeDepsMap, originalJar, _, info)
                )
              case Some(info: AutomaticModule) =>
                if (originalJar.moduleName.isDefined)
                  log.warn(s"Already be a module $id -> $originalJar")
                genIfNotExist(
                  moduleJar,
                  addAutomaticModuleName(args.artifacts, originalJar, _, info)
                )
              case None =>
                if (originalJar.moduleName.isDefined) originalJar
                else if (!failOnMissing) {
                  log.warn(s"Not a module and no mapping defined: $id -> $originalJar")
                  originalJar
                } else
                  sys.error(s"Not a module and no mapping defined: $id -> $originalJar")
            }
            id -> List(remappedJar)
          }
        }
        .toMap
      report.withConfigurations(report.configurations.map { c =>
        c.withModules(c.modules.flatMap { mod =>
          remapped.get(mod.module.jmodId) match {
            case None                    => mod +: Nil
            case Some(Nil)               => Nil
            case Some(List(remappedJar)) => withJar(mod, remappedJar) +: Nil
          }
        })
      })
    }
  }

  private def moduleInfoGenClass: Def.Initialize[Task[Option[File]]] = Def.taskDyn {
    moduleInfo.value match {
      case _: KnownModule | _: AutomaticModule => Def.task(None)
      case info0: JpmsModule =>
        Def.taskDyn {
          val classDir = (Compile / classDirectory).value
          val f = classDir / "module-info.class"
          val info1 =
            if (!info0.exportAll) info0
            else
              info0.copy(exports =
                PathFinder(classDir)
                  .glob(DirectoryFilter)
                  .globRecursive("*.class")
                  .get()
                  .map { f => IO.relativize(classDir, f.getParentFile).get }
                  .toSet ++ info0.exports
              )
          val info = info1.copy(
            moduleVersion = Option(info1.moduleVersion).getOrElse(version.value),
            mainClass = Option(info1.mainClass).getOrElse((Compile / run / mainClass).value)
          )

          Def.taskIf {
            if (info.requireAll) {
              val is = scalaModuleInfo.value
              val args = new ModuleInfoArgs(
                moduleInfos.value ++ dependentProjectModules.value,
                classpathTypes.value,
                update.value
              )
              val requires = allDependencies.value.flatMap { m =>
                if (isRuntimeDepend(m.configurations)) {
                  val tpe = if (m.isTransitive) Require.Transitive else Require.Default
                  val require = args.idToModuleName(m.jmodId(is)) -> tpe
                  require +: Nil
                } else Nil
              }.toSet
              genIfNotExist(f, IO.write(_, info.copy(requires = info.requires ++ requires).toModuleInfoClass))
              Some(f)
            } else {
              genIfNotExist(f, IO.write(_, info.toModuleInfoClass))
              Some(f)
            }
          }
        }
    }
  }

  // TODO cache and re-generate when needed
  private def genIfNotExist(f: File, gen: File => Unit) =
    if (f.isFile) f
    else { gen(f); f.ensuring(_.isFile) }

  private def jarOf(mod: ModuleReport) = mod.artifacts.collect {
    case (a, f) if a.extension == "jar" => f
  } match {
    case Vector(f) => f
    case arts      => sys.error(s"Cant find art for ${mod.module.jmodId}. $arts")
  }
  private def withJar(mod: ModuleReport, remappedJar: File) = mod.withArtifacts(mod.artifacts.map {
    case x @ (a, _) if a.extension != "jar" => x
    case (a, _)                             => a -> remappedJar
  })
}
