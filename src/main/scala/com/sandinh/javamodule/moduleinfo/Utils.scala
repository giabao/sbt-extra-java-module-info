package com.sandinh.javamodule.moduleinfo

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.*
import org.objectweb.asm.tree.ClassNode
import sbt.{CrossVersion, File, ModuleID}
import sbt.io.Using
import sbt.librarymanagement.ScalaModuleInfo

import java.lang.Boolean.parseBoolean
import java.nio.file.Files
import java.util.jar.{JarEntry, JarInputStream, JarOutputStream, Manifest}
import scala.collection.compat.immutable.LazyList

object Utils {
  def usingJos[R](jar: File, man: Manifest = null)(f: JarOutputStream => R): R =
    Using.resource { (f: File) =>
      val out = Files.newOutputStream(f.toPath)
      if (man == null) new JarOutputStream(out)
      else new JarOutputStream(out, man)
    }(jar)(f)

  def toSlash(fqdn: String): String = fqdn.replace('.', '/')

  implicit class ModuleIDOps(val m: ModuleID) extends AnyVal {
    def jmodId: String = s"${m.organization}:${m.name}"
    def jmodId(is: Option[ScalaModuleInfo]): String =
      CrossVersion(m, is).getOrElse(identity[String] _)(m.jmodId)

    def containsConfiguration(c: String): Boolean = m.configurations.forall { cs =>
      cs.split(';').map(_.replace(" ", "")).exists(s => s == c || s.startsWith(s"$c->"))
    }
  }

  implicit class JarInputStreamOps(val jis: JarInputStream) extends AnyVal {
    def isMultiReleaseJar: Boolean = {
      val man = jis.getManifest
      man != null && parseBoolean(man.getMainAttributes.getValue("Multi-Release"))
    }

    def lazyList: LazyList[JarEntry] = LazyList.continually(jis.getNextJarEntry).takeWhile(_ != null)

    def getOrCreateManifest: Manifest = jis.getManifest match {
      case null =>
        val m = new Manifest
        m.getMainAttributes.putValue("Manifest-Version", "1.0")
        m
      case m => m
    }
  }

  private val ModuleInfoClassMjarPath = "META-INF/versions/\\d+/module-info.class".r.pattern

  implicit class JarFileOps(val jar: File) extends AnyVal {
    def isAutoModule: Boolean = Using.jarInputStream(Files.newInputStream(jar.toPath)) { jis =>
      val man = jis.getManifest
      man != null && man.getMainAttributes.getValue(
        "Automatic-Module-Name"
      ) != null
    }

    def isModule: Boolean = Using.jarInputStream(Files.newInputStream(jar.toPath)) { jis =>
      val isMultiReleaseJar = jis.isMultiReleaseJar
      jis.lazyList.map(_.getName).exists {
        case "module-info.class" => true
        case name                => isMultiReleaseJar && ModuleInfoClassMjarPath.matcher(name).matches()
      }
    }

    def moduleName: Option[String] = Using.jarInputStream(Files.newInputStream(jar.toPath)) { jis =>
      val man = Option(jis.getManifest)
      man
        .flatMap { m =>
          Option(m.getMainAttributes.getValue("Automatic-Module-Name"))
        }
        .orElse {
          val isMultiReleaseJar = man.fold(false) { m =>
            parseBoolean(m.getMainAttributes.getValue("Multi-Release"))
          }

          jis.lazyList
            .find { e =>
              e.getName == "module-info.class" ||
              isMultiReleaseJar && ModuleInfoClassMjarPath.matcher(e.getName).matches()
            }
            .map { _ =>
              val cr = new ClassReader(jis)
              val classNode = new ClassNode
              cr.accept(classNode, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES)
              classNode.module.name
            }
        }
    }
  }
}
