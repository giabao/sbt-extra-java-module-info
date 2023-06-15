package com.sandinh.javamodule.moduleinfo

import org.jetbrains.annotations.Nullable
import org.objectweb.asm.{ClassWriter, Opcodes}
import sbt.*
import sbt.Keys.*
import sbt.io.{IO, Using}
import sbt.librarymanagement.Configurations.{Compile, Runtime}

import java.nio.file.Files
import java.util.jar.{JarEntry, JarInputStream, JarOutputStream, Manifest}
import java.util.zip.ZipException
import scala.io.Codec.UTF8
import scala.io.Source
import Utils.*

object ExtraJavaModuleInfoTransform {
  private val JarSignaturePath = "^META-INF/[^/]+\\.(SF|RSA|DSA|sf|rsa|dsa)$".r.pattern
  private val ServicesPrefix = "META-INF/services/"

  /** Convenient class to wrap providers and packages arguments */
  private case class PP(providers: Map[String, List[String]], packages: Set[String])
  private object PP { val empty: PP = PP(Map.empty, Set.empty) }

  def addAutomaticModuleName(
      artifacts: Classpath,
      originalJar: File,
      moduleJar: File,
      automaticModule: AutomaticModuleName
  ): Unit = Using.jarInputStream(Files.newInputStream(originalJar.toPath)) { jis =>
    val man = jis.getManifest match {
      case null =>
        val m = new Manifest
        m.getMainAttributes.putValue("Manifest-Version", "1.0")
        m
      case m => m
    }
    man.getMainAttributes.putValue("Automatic-Module-Name", automaticModule.moduleName)
    usingJos(moduleJar, man) { jos =>
      val pp = copyAndExtractProviders(jis, jos, automaticModule.mergedJars.nonEmpty, PP.empty)
      mergeJars(artifacts, automaticModule, jos, pp)
    }
  }

  def addModuleDescriptor(
      args: ModuleInfoArgs,
      originalJar: File,
      moduleJar: File,
      moduleInfo: JModuleInfo,
  ): Unit = Using.jarInputStream(Files.newInputStream(originalJar.toPath)) { jis =>
    usingJos(moduleJar, jis.getManifest) { jos =>
      var pp = copyAndExtractProviders(jis, jos, moduleInfo.mergedJars.nonEmpty, PP.empty)
      pp = mergeJars(args.artifacts, moduleInfo, jos, pp)
      jos.putNextEntry(new JarEntry("module-info.class"))
      val version = args.artifacts.collectFirst {
        case a if a.data == originalJar => a.get(moduleID.key).get.revision
      }.orNull
      jos.write(
        addModuleInfo(
          args,
          moduleInfo,
          pp.providers,
          version,
          if (moduleInfo.exportAll) pp.packages else Set.empty
        )
      )
      jos.closeEntry()
    }
  }

  private def copyAndExtractProviders(
      jis: JarInputStream,
      jos: JarOutputStream,
      willMergeJars: Boolean,
      pp: PP
  ): PP = {
    var PP(providers, packages) = pp
    jis.lazyList.foreach { jarEntry =>
      val content = IO.readBytes(jis)
      val entryName = jarEntry.getName
      val isFileInServicesFolder = entryName.startsWith(ServicesPrefix) && entryName != ServicesPrefix
      if (isFileInServicesFolder) {
        val key = entryName.substring(ServicesPrefix.length)
        val isServiceProviderFile = !key.contains("/") // ignore files in sub-folders
        if (isServiceProviderFile) {
          providers = providers.updated(
            key,
            providers.getOrElse(key, Nil) ++ extractImplementations(content)
          )
        }
      }
      if (
        !JarSignaturePath.matcher(entryName).matches && "META-INF/MANIFEST.MF" != jarEntry.getName &&
        (!willMergeJars || !isFileInServicesFolder) // service provider files will be merged later
      ) {
        jarEntry.setCompressedSize(-1)
        try {
          jos.putNextEntry(jarEntry)
          jos.write(content)
          jos.closeEntry()
        } catch {
          case e: ZipException =>
            if (!e.getMessage.startsWith("duplicate entry:")) throw new RuntimeException(e)
        }
        if (entryName.endsWith(".class")) {
          val i = entryName.lastIndexOf("/")
          if (i > 0) packages += entryName.substring(0, i)
        }
      }
    }
    PP(providers, packages)
  }

  private def extractImplementations(content: Array[Byte]) =
    Source
      .fromBytes(content)(UTF8)
      .getLines()
      .map(_.trim)
      .filter { line => line.nonEmpty && !line.startsWith("#") }
      .toList
      .distinct

  private def addModuleInfo(
      args: ModuleInfoArgs,
      moduleInfo: JModuleInfo,
      providers: Map[String, List[String]],
      @Nullable version: String,
      autoExportedPackages: Set[String],
  ) = {
    val classWriter = new ClassWriter(0)
    classWriter.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
    val openModule = if (moduleInfo.openModule) Opcodes.ACC_OPEN else 0
    val moduleVersion = Option(moduleInfo.moduleVersion).getOrElse(version)
    val moduleVisitor = classWriter.visitModule(moduleInfo.moduleName, openModule, moduleVersion)
    autoExportedPackages.foreach(moduleVisitor.visitExport(_, 0))
    moduleInfo.exports.map(toSlash).foreach(moduleVisitor.visitExport(_, 0))
    moduleInfo.opens.map(toSlash).foreach(moduleVisitor.visitOpen(_, 0))
    moduleVisitor.visitRequire("java.base", 0, null)
    if (moduleInfo.requireAll) {
      val compileDeps = args.compileDeps.getOrElse(moduleInfo.id, Set.empty)
      val runtimeDeps = args.runtimeDeps.getOrElse(moduleInfo.id, Set.empty)
      if (compileDeps.isEmpty && runtimeDeps.isEmpty)
        throw new RuntimeException(
          s"[requires directives from metadata] Cannot find dependencies for '${moduleInfo.moduleName}'. " +
            s"Are '${moduleInfo.id}' the correct component coordinates?"
        )
      val allDeps = compileDeps ++ runtimeDeps
      for (ga <- allDeps) {
        val moduleName = args.gaToModuleName(ga)
        if (compileDeps.contains(ga) && runtimeDeps.contains(ga))
          moduleVisitor.visitRequire(moduleName, Opcodes.ACC_TRANSITIVE, null)
        else if (runtimeDeps.contains(ga)) moduleVisitor.visitRequire(moduleName, 0, null)
        else if (compileDeps.contains(ga))
          moduleVisitor.visitRequire(moduleName, Opcodes.ACC_STATIC_PHASE, null)
      }
    }
    moduleInfo.requires.foreach(moduleVisitor.visitRequire(_, 0, null))
    moduleInfo.requiresTransitive.foreach(moduleVisitor.visitRequire(_, Opcodes.ACC_TRANSITIVE, null))
    moduleInfo.requiresStatic.foreach(moduleVisitor.visitRequire(_, Opcodes.ACC_STATIC_PHASE, null))
    moduleInfo.uses.map(toSlash).foreach(moduleVisitor.visitUse)
    providers.foreach { case (name, implementations) =>
      if (!moduleInfo.ignoreServiceProviders.contains(name))
        moduleVisitor.visitProvide(
          toSlash(name),
          implementations.map(toSlash)*
        )
    }
    moduleVisitor.visitEnd()
    classWriter.visitEnd()
    classWriter.toByteArray
  }

  private def mergeJars(
      artifacts: Classpath,
      moduleSpec: ModuleSpec,
      outputStream: JarOutputStream,
      pp0: PP
  ): PP = {
    if (moduleSpec.mergedJars.isEmpty) return pp0
    var pp = pp0
    for (identifier <- moduleSpec.mergedJars) {
      artifacts.collectFirst {
        case a if a.get(moduleID.key).get.jmodId == identifier => a.data
      } match {
        case Some(mergeJarFile) =>
          Using.jarInputStream(new JarInputStream(Files.newInputStream(mergeJarFile.toPath))) { jis =>
            pp = copyAndExtractProviders(jis, outputStream, true, pp)
          }
        case None => throw new RuntimeException("Jar not found: " + identifier)
      }
    }
    mergeServiceProviderFiles(outputStream, pp.providers)
    pp
  }

  private def mergeServiceProviderFiles(jos: JarOutputStream, providers: Map[String, List[String]]): Unit =
    providers.foreach { case (k, v) =>
      jos.putNextEntry(new JarEntry(ServicesPrefix + k))
      for (implementation <- v) {
        jos.write(implementation.getBytes)
        jos.write("\n".getBytes)
      }
      jos.closeEntry()
    }
}

private class ModuleInfoArgs(
    infos: Seq[ModuleSpec],
    jarTypes: Set[String],
    up: UpdateReport,
    val compileDeps: Map[String, Set[String]],
    val runtimeDeps: Map[String, Set[String]],
) {
  private lazy val allInfos = infos ++ artifacts.flatMap { a =>
    def id = a.get(moduleID.key).get.jmodId
    a.data.moduleName.toSeq.map(KnownModule(id, _))
  }
  def gaToModuleName(ga: String): String = allInfos
    .find(_.id == ga)
    .fold(
      throw new RuntimeException(
        s"""[requires directives from metadata] The module name of the following component is not known: $ga
             | - If it is already a module, make the module name known using 'knownModule("$ga", "<module name>")'
             | - If it is not a module, patch it using 'module()' or 'automaticModule()'""".stripMargin
      )
    )(_.moduleName)
  // @see managedClasspath
  lazy val artifacts: Classpath = up
    .filter(
      configurationFilter(Compile.name | Runtime.name) && artifactFilter(`type` = jarTypes)
    )
    .toSeq
    .map { case (_, module, _, file) =>
      Attributed(file)(AttributeMap.empty.put(moduleID.key, module))
    }
    .distinct
}
