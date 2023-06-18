package com.sandinh.javamodule.moduleinfo

import sbt.*
import sbt.Keys.*
import Utils.*
import ExtraJavaModuleInfoTransform.{addAutomaticModuleName, addModuleDescriptor, moduleInfoClass}
import sbt.librarymanagement.Configurations.RuntimeInternal
import sbt.sandinh.DependencyTreeAccess
import sbt.sandinh.DependencyTreeAccess.{moduleInfoDepGraph, toDepsMap}

object ModuleInfoPlugin extends AutoPlugin {
  object autoImport {
    val moduleInfo = settingKey[PlainModuleInfo](
      "Java module-info to generate module-info.class for this project"
    )
    val moduleInfos = settingKey[Seq[ModuleSpec]](
      "extra Java module-info to patch non-module jar dependencies"
    )
    val moduleInfoFailOnMissing = settingKey[Boolean](
      "Fail update task if exist non-module jar dependencies and no ModuleSpec is defined for it"
    )
  }
  import autoImport.*

  override def projectSettings: Seq[Setting[?]] =
    DependencyTreeAccess.settings ++ Seq(
      moduleInfos := Nil,
      moduleInfoFailOnMissing := false,
      Compile / products ++= genModuleInfoClass.value,
      update := {
        val report = update.value
        val log = streams.value.log
        val infos = moduleInfos.value
        val out = target.value / "moduleInfo"
        out.mkdirs()
        val failOnMissing = moduleInfoFailOnMissing.value
        val args = new ModuleInfoArgs(
          infos,
          classpathTypes.value,
          report,
          toDepsMap((Compile / moduleInfoDepGraph).value),
          toDepsMap((Runtime / moduleInfoDepGraph).value)
        )
        val remapped = report
          .configuration(RuntimeInternal)
          .get
          .modules
          .map { mod =>
            val id = mod.module.jmodId
            if (infos.exists(_.mergedJars.contains(id))) id -> Nil
            else {
              val originalJar = jarOf(mod)
              val moduleJar = out / originalJar.name
              val remappedJar = infos.find(_.id == id) match {
                case Some(_: KnownModule) => originalJar
                case Some(moduleInfo: JModuleInfo) =>
                  genIfNotExist(moduleJar, addModuleDescriptor(args, originalJar, _, moduleInfo))
                case Some(moduleSpec: AutomaticModuleName) =>
                  genIfNotExist(
                    moduleJar,
                    addAutomaticModuleName(args.artifacts, originalJar, _, moduleSpec)
                  )
                case None =>
                  if (originalJar.isModule || originalJar.isAutoModule) originalJar
                  else if (!failOnMissing) {
                    log.warn(
                      s"Not a module and no mapping defined: $id -> $originalJar"
                    )
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
      },
    )

  private val genModuleInfoClass = Def.task {
    val f = (Compile / classDirectory).value / "module-info.class"
    val mainCls = (Compile / run / mainClass).value
    moduleInfo.?.value.map { info =>
      genIfNotExist(f, IO.write(_, moduleInfoClass(info, mainCls)))
    }
  }
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
