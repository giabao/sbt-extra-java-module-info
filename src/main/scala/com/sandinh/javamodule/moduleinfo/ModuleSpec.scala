package com.sandinh.javamodule.moduleinfo

import org.jetbrains.annotations.Nullable

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
    requireAllDefinedDependencies: Bool = Bool.Default,
    exportAllPackages: Bool = Bool.Default,
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
) extends ModuleSpec

sealed trait Bool {
  def apply(default: Boolean): Boolean = this match {
    case Bool.Yes     => true
    case Bool.No      => false
    case Bool.Default => default
  }
}
object Bool {
  case object Yes extends Bool
  case object No extends Bool
  case object Default extends Bool
}
