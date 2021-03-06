package scala.meta.internal.semanticdb.scalac

import scala.meta.internal.index._
import scala.tools.nsc.Global

trait SemanticdbOps
    extends AnnotationOps
    with SymbolInformationOps
    with TextDocumentOps
    with InputOps
    with LanguageOps
    with DiagnosticOps
    with NameOps
    with ParseOps
    with PrinterOps
    with ReporterOps
    with ReflectionToolkit
    with SymbolOps
    with TypeOps {
  val global: Global
  var config: SemanticdbConfig = SemanticdbConfig.default
  val index: Index = new Index
}
