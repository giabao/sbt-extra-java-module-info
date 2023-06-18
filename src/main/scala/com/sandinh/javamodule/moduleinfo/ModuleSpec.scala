package com.sandinh.javamodule.moduleinfo

import org.jetbrains.annotations.Nullable
import org.objectweb.asm.Opcodes

sealed trait ModuleSpec {

  /** group:name coordinates */
  def id: String

  /** the Module Name of the Module to construct */
  def moduleName: String

  /** all merged Jar identifiers */
  def mergedJars: List[String]
}

final case class KnownModule(
    id: String,
    moduleName: String,
) extends ModuleSpec {
  def mergedJars: List[String] = Nil
}

final case class AutomaticModuleName(
    id: String,
    moduleName: String,
    mergedJars: List[String] = Nil,
) extends ModuleSpec

final case class JModuleInfo(
    id: String,
    moduleName: String,
    mergedJars: List[String] = Nil,
    @Nullable moduleVersion: String = null,
    openModule: Boolean = true,
    exports: Set[String] = Set.empty,
    opens: Set[String] = Set.empty,
    requires: Set[String] = Set.empty,
    requiresTransitive: Set[String] = Set.empty,
    requiresStatic: Set[String] = Set.empty,
    uses: Set[String] = Set.empty,
    ignoreServiceProviders: Set[String] = Set.empty,
    /** Default = true if `(requires ++ requiresTransitive ++ requiresStatic).isEmpty` */
    @Nullable private val requireAllDefinedDependencies: java.lang.Boolean = null,
    /** Default = true if `exports.isEmpty` */
    @Nullable private val exportAllPackages: java.lang.Boolean = null,
) extends ModuleSpec {
  def requireAll: Boolean =
    if (requireAllDefinedDependencies != null) requireAllDefinedDependencies
    else requires.isEmpty && requiresTransitive.isEmpty && requiresStatic.isEmpty

  def exportAll: Boolean =
    if (exportAllPackages != null) exportAllPackages
    else exports.isEmpty

  def toPlainModuleInfo: PlainModuleInfo = PlainModuleInfo(
    moduleName,
    moduleVersion,
    openModule,
    exports,
    opens,
    requires.map(_ -> Require.Default) ++
      requiresTransitive.map(_ -> Require.Transitive) ++
      requiresStatic.map(_ -> Require.Static),
    uses,
  )
}

final case class PlainModuleInfo(
    moduleName: String,
    @Nullable moduleVersion: String = null,
    openModule: Boolean = true,
    exports: Set[String] = Set.empty,
    opens: Set[String] = Set.empty,
    requires: Set[(String, Require)] = Set.empty,
    uses: Set[String] = Set.empty,
    providers: Map[String, List[String]] = Map.empty,
)
sealed trait Require {
  final def code: Int = this match {
    case Require.Default    => 0
    case Require.Transitive => Opcodes.ACC_TRANSITIVE
    case Require.Static     => Opcodes.ACC_STATIC_PHASE
  }
}
object Require {
  case object Default extends Require
  case object Transitive extends Require
  case object Static extends Require
}
