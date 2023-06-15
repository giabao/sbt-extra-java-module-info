package com.sandinh.javamodule.moduleinfo

import sbt.*
import sbt.Keys.*
import Utils.*
import ExtraJavaModuleInfoTransform.{addAutomaticModuleName, addModuleDescriptor}
import sbt.Project.inConfig
import sbt.librarymanagement.Configurations.Compile
import sbt.plugins.DependencyTreeSettings
import sbt.plugins.MiniDependencyTreeKeys.dependencyTreeIncludeScalaLibrary
import sbt.sandinh.DependencyTreeAccess
import sbt.sandinh.DependencyTreeAccess.{moduleInfoDepGraph, toDepsMap}

object ModuleInfoPlugin extends AutoPlugin {
  object autoImport {
    val moduleInfos = settingKey[Seq[ModuleSpec]]("extraJavaModuleInfo")
    val moduleInfoFailOnMissing = settingKey[Boolean](
      "Fail update task if exist non-module jar dependencies and no ModuleSpec is defined for it"
    )
  }
  import autoImport.*

  override def projectSettings: Seq[Setting[?]] =
    DependencyTreeAccess.settings ++ Seq(
      moduleInfos := Nil,
      moduleInfoFailOnMissing := false,
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
        val configs = report.configurations.map {
          case c if !interestedConfigs.contains(c.configuration) => c
          case c =>
            c.withModules(c.modules.flatMap { mod =>
              val id = mod.module.jmodId
              val willBeMerged = infos.exists(_.mergedJars.contains(id))
              if (willBeMerged) Nil
              else {
                val originalJar = mod.artifacts.collect {
                  case (a, f) if a.extension == "jar" => f
                } match {
                  case Vector(f) => f
                  case arts      => sys.error(s"Cant find art for $id. $arts")
                }

                def moduleJar = out / originalJar.name

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
                        s"[${c.configuration.name}] Not a module and no mapping defined: $id -> $originalJar"
                      )
                      originalJar
                    } else
                      throw new RuntimeException(
                        s"Not a module and no mapping defined: $id -> $originalJar"
                      )
                }
                mod.withArtifacts(mod.artifacts.map {
                  case x @ (a, _) if a.extension != "jar" => x
                  case (a, _)                             => a -> remappedJar
                }) +: Nil
              }
            })
        }
        report.withConfigurations(configs)
      },
    )

  private def genIfNotExist(f: File, gen: File => Unit) =
    if (f.isFile) f
    else { gen(f); f.ensuring(_.isFile) }

  private val interestedConfigs = Seq(Compile, Runtime).map(_.toConfigRef)
}
