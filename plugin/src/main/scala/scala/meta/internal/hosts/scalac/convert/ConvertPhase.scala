package scala.meta
package internal.hosts.scalac
package convert

import scala.tools.nsc.{Global, Phase, SubComponent}
import scala.tools.nsc.plugins.{Plugin => NscPlugin, PluginComponent => NscPluginComponent}
import scala.meta.internal.hosts.scalac.{PluginBase => ScalahostPlugin}
import scala.reflect.io.AbstractFile
import org.scalameta.reflection._

import scala.meta.internal.{ ast => api}
import scala.meta.tql._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import scala.collection.{immutable => imm}

trait ConvertPhase {
  self: ScalahostPlugin =>

  object ConvertComponent extends NscPluginComponent {
    val global: self.global.type = self.global
    import global._

    // TODO: ideally we would like to save everything after the very end of typechecking, which is after refchecks
    // but unfortunately by then a lot of semantic stuff is already desugared to death (patmat, superaccessors, some code in refchecks)
    // therefore we run after typer and hope for the best (i.e. that we don't run into nonsense that we don't know how to convert,
    // and also that we don't encounter residual cyclic reference errors which are the reason why certain typechecks are delayed past typer)
    // btw this isn't such a big problem for persistence, but it definitely is for macro interpretation
    // let's hope that the research into runtime macros, which entails moving the typechecker to scala-reflect.jar will allow us to restructure things
    // so that delayed typechecks come right after typer, not intermingled with other logic
    override val runsAfter = List("typer")
    override val runsRightAfter = None
    val phaseName = "convert"
    override def description = "convert compiler trees to scala.meta"
    implicit val c = Scalahost.mkGlobalContext[global.type](global)

    override def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {

      // TODO: known bug: sometimes, Pat.Var.Term has its name not properly chaned
      // TODO: known bug: Term.ApplyType(Ctor.Ref.Name, ...) sometimes has its name not properly charged with semantic informations as well.
      // TODO: cleanup, perhaps re-write using macros.
      private def merge(parsedTree: api.Source, convertedTree: api.Source): api.Source = {

        def zLoop[T](pTree: imm.Seq[T], cTree: imm.Seq[T], f: (T, T) => api.Tree): imm.Seq[T] = (pTree zip cTree).map(s => f(s._1, s._2).asInstanceOf[T])

        def zzLoop[T](pTree: imm.Seq[imm.Seq[T]], cTree: imm.Seq[imm.Seq[T]], f: (T, T) => api.Tree): imm.Seq[imm.Seq[T]] = {
          (pTree zip cTree).map(xs => (xs._1 zip xs._2).map(x => f(x._1, x._2).asInstanceOf[T]))
        }

        def oLoop[T](pTree: Option[T], cTree: Option[T], f: (T, T) => api.Tree): Option[T] = (pTree, cTree) match {
          case (Some(p), Some(c)) => Some(f(p, c).asInstanceOf[T])
          case (None, None) => None
          case _ =>
            reporter.warning(NoPosition, "An error occured while merging the parsed and the converted tree. The trees where not identical. This should never happen.")
            pTree
        }

        def loop[T <: api.Tree](pTree: T, cTree: T): T = {
          import api._
          ((pTree, cTree) match {

            case (p: Term.Name, c: Term.Name) =>
              p.copy(denot = c.denot, sigma = c.sigma)
            case (p: Type.Name, c: Type.Name) =>
              p.copy(denot = c.denot, sigma = c.sigma)
            case (p: Ctor.Ref.Name, c: Ctor.Ref.Name) =>
              p.copy(denot = c.denot, sigma = c.sigma)
            case (p: Name.Anonymous, c: Name.Anonymous) =>
              p.copy(denot = c.denot, sigma = c.sigma)
            case (p: Name.Indeterminate, c: Name.Indeterminate) =>
              p.copy(denot = c.denot, sigma = c.sigma)

            case (p: Term.This, c: Term.This) =>
              p.copy(loop(p.qual, c.qual))
            case (p: Term.Super, c: Term.Super) =>
              p.copy(loop(p.thisp, c.thisp), loop(p.superp, c.superp))
            case (p: Term.Select, c: Term.Select) =>
              p.copy(loop(p.qual, c.qual), loop(p.name, c.name))
            case (p: Term.Interpolate, c: Term.Interpolate) =>
              p.copy(loop(p.prefix, c.prefix), zLoop(p.parts, c.parts, loop[Lit]), zLoop(p.args, c.args, loop[Term]))
            case (p: Term.Apply, c: Term.Apply) =>
              p.copy(loop(p.fun, c.fun), zLoop(p.args, c.args, loop[Term.Arg]))
            case (p: Term.ApplyType, c: Term.ApplyType) =>
              p.copy(loop(p.fun, c.fun), zLoop(p.targs, c.targs, loop[Type]))
            case (p: Term.ApplyInfix, c: Term.ApplyInfix) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.op, c.op), zLoop(p.targs, c.targs, loop[Type]), zLoop(p.args, c.args, loop[Term.Arg]))
            case (p: Term.ApplyUnary, c: Term.ApplyUnary) =>
              p.copy(loop(p.op, c.op), loop(p.arg, c.arg))
            case (p: Term.Assign, c: Term.Assign) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.rhs, c.rhs))
            case (p: Term.Update, c: Term.Update) =>
              p.copy(loop(p.fun, c.fun), zzLoop(p.argss, c.argss, loop[Term.Arg]), loop(p.rhs, c.rhs))
            case (p: Term.Return, c: Term.Return) =>
              p.copy(loop(p.expr, c.expr))
            case (p: Term.Throw, c: Term.Throw) =>
              p.copy(loop(p.expr, c.expr))
            case (p: Term.Ascribe, c: Term.Ascribe) =>
              p.copy(loop(p.expr, c.expr), loop(p.tpe, c.tpe))
            case (p: Term.Annotate, c: Term.Annotate) =>
              p.copy(loop(p.expr, c.expr), zLoop(p.annots, c.annots, loop[Mod]))
            case (p: Term.Tuple, c: Term.Tuple) =>
              p.copy(zLoop(p.elements, c.elements, loop[Term]))
            case (p: Term.Block, c: Term.Block) =>
              p.copy(zLoop(p.stats, c.stats, loop[Stat]))
            case (p: Term.If, c: Term.If) =>
              p.copy(loop(p.cond, c.cond), loop(p.thenp, c.thenp), loop(p.elsep, c.elsep))
            case (p: Term.Match, c: Term.Match) =>
              p.copy(loop(p.scrut, c.scrut), zLoop(p.cases, c.cases, loop[Case]))
            case (p: Term.TryWithCases, c: Term.TryWithCases) =>
              p.copy(loop(p.expr, c.expr), zLoop(p.catchp, c.catchp, loop[Case]), oLoop(p.finallyp, c.finallyp, loop[Term]))
            case (p: Term.TryWithTerm, c: Term.TryWithTerm) =>
              p.copy(loop(p.expr, c.expr), loop(p.catchp, c.catchp), oLoop(p.finallyp, c.finallyp, loop[Term]))
            case (p: Term.Function, c: Term.Function) =>
              p.copy(zLoop(p.params, c.params, loop[Term.Param]), loop(p.body, c.body))
            case (p: Term.PartialFunction, c: Term.PartialFunction) =>
              p.copy(zLoop(p.cases, c.cases, loop[Case]))
            case (p: Term.While, c: Term.While) =>
              p.copy(loop(p.expr, c.expr), loop(p.body, c.body))
            case (p: Term.Do, c: Term.Do) =>
              p.copy(loop(p.body, c.body), loop(p.expr, c.expr))
            case (p: Term.For, c: Term.For) =>
              p.copy(zLoop(p.enums, c.enums, loop[Enumerator]), loop(p.body, c.body))
            case (p: Term.ForYield, c: Term.ForYield) =>
              p.copy(zLoop(p.enums, c.enums, loop[Enumerator]), loop(p.body, c.body))
            case (p: Term.New, c: Term.New) =>
              p.copy(loop(p.templ, c.templ))
            case (p: Term.Placeholder, c: Term.Placeholder) =>
              p
            case (p: Term.Eta, c: Term.Eta) =>
              p.copy(loop(p.term, c.term))

            // Handling special cases
            case (p: Term.Block, c: Stat) if p.stats.length == 1 =>
              p.copy(imm.Seq(loop(p.stats.head, c)))
            case (p: Term.Interpolate, c: Term.Apply) =>
              p // TODO: this is due to quasiquote expansion - should probably be left as is in the parsed tree.

            case (p: Term.Arg.Named, c: Term.Arg.Named) =>
              p.copy(loop(p.name, c.name), loop(p.rhs, c.rhs))
            case (p: Term.Arg.Repeated, c: Term.Arg.Repeated) =>
              p.copy(loop(p.arg, c.arg))

            case (p: Term.Param, c: Term.Param) =>
              p.copy(
                zLoop(p.mods, c.mods, loop[Mod]),
                loop(p.name, c.name),
                oLoop(p.decltpe, c.decltpe, loop[Type.Arg]),
                oLoop(p.default, c.default, loop[Term])
              )

            case (p: Type.Select, c: Type.Select) =>
              p.copy(loop(p.qual, c.qual), loop(p.name, c.name))
            case (p: api.Type.Project, c: Type.Project) =>
              p.copy(loop(p.qual, c.qual), loop(p.name, c.name))
            case (p: Type.Singleton, c: Type.Singleton) =>
              p.copy(loop(p.ref, c.ref))
            case (p: Type.Apply, c: api.Type.Apply) =>
              p.copy(loop(p.tpe, c.tpe), zLoop(p.args, c.args, loop[Type]))
            case (p: Type.ApplyInfix, c: Type.ApplyInfix) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.op, c.op), loop(p.rhs, c.rhs))
            case (p: Type.Function, c: Type.Function) =>
              p.copy(zLoop(p.params, c.params, loop[Type.Arg]), loop(p.res, c.res))
            case (p: Type.Tuple, c: Type.Tuple) =>
              p.copy(zLoop(p.elements, c.elements, loop[Type]))
            case (p: Type.Compound, c: Type.Compound) =>
              p.copy(zLoop(p.tpes, c.tpes, loop[Type]), zLoop(p.refinement, c.refinement, loop[Stat]))
            case (p: Type.Existential, c: Type.Existential) =>
              p.copy(loop(p.tpe, c.tpe), zLoop(p.quants, c.quants, loop[Stat]))
            case (p: Type.Annotate, c: Type.Annotate) =>
              p.copy(loop(p.tpe, c.tpe), zLoop(p.annots, c.annots, loop[Mod]))
            case (p: Type.Placeholder, c: Type.Placeholder) =>
              p

            // Handling special cases
            case (p: api.Type.Name, c: api.Type.Select) =>
              loop(p, c.name)

            case (p: Type.Arg.ByName, c: Type.Arg.ByName) =>
              p.copy(loop(p.tpe, c.tpe))
            case (p: Type.Arg.Repeated, c: Type.Arg.Repeated) =>
              p.copy(loop(p.tpe, c.tpe))

            case (p: Type.Param, c: Type.Param) =>
              p.copy(
                zLoop(p.mods, c.mods, loop[Mod]),
                loop(p.name, c.name),
                zLoop(p.tparams, c.tparams, loop[Type.Param]),
                loop(p.typeBounds, c.typeBounds),
                zLoop(p.viewBounds, c.viewBounds, loop[Type]),
                zLoop(p.contextBounds, c.contextBounds, loop[Type])
              )
            case (p: Type.Bounds, c: Type.Bounds) =>
              p.copy(oLoop(p.lo, c.lo, loop[Type]), oLoop(p.hi, c.hi, loop[Type]))

            case (p: Decl.Val, c: Decl.Val) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), zLoop(p.pats, c.pats, loop[Pat]), loop(p.decltpe, c.decltpe))
            case (p: Decl.Var, c: Decl.Var) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), zLoop(p.pats, c.pats, loop[Pat]), loop(p.decltpe, c.decltpe))
            case (p: Decl.Def, c: Decl.Def) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), zzLoop(p.paramss, c.paramss, loop[Term.Param]), loop(p.decltpe, c.decltpe))
            case (p: Decl.Type, c: Decl.Type) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), loop(p.bounds, c.bounds))
            case (p: Defn.Val, c: Defn.Val) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), zLoop(p.pats, c.pats, loop[Pat]), oLoop(p.decltpe, c.decltpe, loop[Type]), loop(p.rhs, c.rhs))
            case (p: Defn.Var, c: Defn.Var) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), zLoop(p.pats, c.pats, loop[Pat]), oLoop(p.decltpe, c.decltpe, loop[Type]), oLoop(p.rhs, c.rhs, loop[Term]))
            case (p: Defn.Def, c: Defn.Def) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), zzLoop(p.paramss, c.paramss, loop[Term.Param]), oLoop(p.decltpe, c.decltpe, loop[Type]), loop(p.body, c.body))
            case (p: Defn.Macro, c: Defn.Macro) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), zzLoop(p.paramss, c.paramss, loop[Term.Param]), loop(p.tpe, c.tpe), loop(p.body, c.body))
            case (p: Defn.Type, c: Defn.Type) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), loop(p.body, c.body))
            case (p: Defn.Class, c: Defn.Class) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), loop(p.ctor, c.ctor), loop(p.templ, c.templ))
            case (p: Defn.Trait, c: Defn.Trait) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zLoop(p.tparams, c.tparams, loop[Type.Param]), loop(p.ctor, c.ctor), loop(p.templ, c.templ))
            case (p: Defn.Object, c: Defn.Object) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), loop(p.ctor, c.ctor), loop(p.templ, c.templ))
            case (p: Pkg, c: Pkg) =>
              p.copy(loop(p.ref, c.ref), zLoop(p.stats, c.stats, loop[Stat]))

            case (p: Import, c: Import) =>
              p.copy(zLoop(p.clauses, c.clauses, loop[Import.Clause]))
            case (p: Import.Clause, c: Import.Clause) =>
              p.copy(loop(p.ref, c.ref), zLoop(p.sels, c.sels, loop[Import.Selector]))
            case (p: Import.Selector.Wildcard, c: Import.Selector.Wildcard) =>
              p
            case (p: Import.Selector.Name, c: Import.Selector.Name) =>
              p.copy(loop(p.value, c.value))
            case (p: Import.Selector.Rename, c: Import.Selector.Rename) =>
              p.copy(loop(p.from, c.from), loop(p.to, c.to))
            case (p: Import.Selector.Unimport, c: Import.Selector.Unimport) =>
              p.copy(loop(p.name, c.name))

            // handling special case
            case (p: Defn.Def, c: Defn.Macro) => p // TODO: this seems to be due to a bug (Quasiquotes are expanded here)
            case (p: Defn.Macro, c: Defn.Def) => p // TODO: this seems to be due to a bug (Quasiquotes are expanded here)

            case (p: Case, c: Case) =>
              p.copy(loop(p.pat, c.pat),oLoop(p.cond, c.cond, loop[Term]),loop(p.body, c.body))

            case (p: Lit, c: Lit) => p

            case (p: Mod.Annot, c: Mod.Annot) =>
              p.copy(loop(p.body, c.body))
            case (p: Mod.Private, c: Mod.Private) =>
              p.copy(loop(p.within, c.within))
            case (p: Mod.Protected, c: Mod.Protected) =>
              p.copy(loop(p.within, c.within))

            case (p: Pat.Var.Term, c: Pat.Var.Term) =>
              p.copy(loop(p.name, c.name))
            case (p: Pat.Wildcard, c: Pat.Wildcard) =>
              p
            case (p: Pat.Bind, c: Pat.Bind) =>
              p.copy(loop(p.lhs, c.lhs), p.rhs.appendScratchpad(c.rhs.scratchpad)) // TODO: check that scratchpad
            case (p: Pat.Alternative, c: Pat.Alternative) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.rhs, c.rhs))
            case (p: Pat.Tuple, c: Pat.Tuple) =>
              p.copy(zLoop(p.elements, c.elements, loop[Pat]))
            case (p: Pat.Extract, c: Pat.Extract) =>
              p.copy(loop(p.ref, c.ref), zLoop(p.targs, c.targs, loop[Type]), zLoop(p.args, c.args, loop[Pat.Arg]))
            case (p: Pat.ExtractInfix, c: Pat.ExtractInfix) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.ref, c.ref), zLoop(p.rhs, c.rhs, loop[Pat.Arg]))
            case (p: Pat.Interpolate, c: Pat.Interpolate) =>
              p.copy(
                loop(p.prefix, c.prefix),
                zLoop(p.parts, c.parts, loop[Lit]),
                zLoop(p.args, c.args, loop[Pat])
              )
            case (p: Pat.Typed, c: Pat.Typed) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.rhs, c.rhs))

            case (p: Pat.Arg, c: Pat.Arg) =>
              p

            case (p: Pat.Var.Type, c: Pat.Var.Type) =>
              p.copy(loop(p.name, c.name))
            case (p: Pat.Type.Project, c: Pat.Type.Project) =>
              p.copy(loop(p.qual, c.qual), loop(p.name, c.name))
            case (p: Pat.Type.Apply, c: Pat.Type.Apply) =>
              p.copy(loop(p.tpe, c.tpe), zLoop(p.args, c.args, loop[Pat.Type]))
            case (p: Pat.Type.ApplyInfix, c: Pat.Type.ApplyInfix) =>
              p.copy(loop(p.lhs, c.lhs), loop(p.op, c.op), loop(p.rhs, c.rhs))
            case (p: Pat.Type.Function, c: Pat.Type.Function) =>
              p.copy(zLoop(p.params, c.params, loop[Pat.Type]), loop(p.res, c.res))
            case (p: Pat.Type.Tuple, c: Pat.Type.Tuple) =>
              p.copy(zLoop(p.elements, c.elements, loop[Pat.Type]))
            case (p: Pat.Type.Compound, c: Pat.Type.Compound) =>
              p.copy(zLoop(p.tpes, c.tpes, loop[Pat.Type]), zLoop(p.refinement, c.refinement, loop[Stat]))
            case (p: Pat.Type.Existential, c: Pat.Type.Existential) =>
              p.copy(loop(p.tpe, c.tpe), zLoop(p.quants, c.quants, loop[Stat]))
            case (p: Pat.Type.Annotate, c: Pat.Type.Annotate) =>
              p.copy(loop(p.tpe, c.tpe), zLoop(p.annots, c.annots, loop[Mod]))

            case (p: Enumerator.Generator, c: Enumerator.Generator) =>
              p.copy(loop(p.pat, c.pat), loop(p.rhs, c.rhs))
            case (p: Enumerator.Val, c: Enumerator.Val) =>
              p.copy(loop(p.pat, c.pat), loop(p.rhs, c.rhs))
            case (p: Enumerator.Guard, c: Enumerator.Guard) =>
              p.copy(loop(p.cond, c.cond))

            case (p: Template, c: Template) =>
              p.copy(zLoop(p.early, c.early, loop[Stat]),
                zLoop(p.parents, c.parents, loop[Term]),
                loop(p.self, c.self),
                (p.stats, c.stats) match {
                  case (Some(p), Some(c)) => Some(zLoop(p, c, loop[Stat]))
                  case (_, _) => None
                }
              )

            case (p: Ctor.Primary, c: Ctor.Primary) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zzLoop(p.paramss, c.paramss, loop[Term.Param]))
            case (p: Ctor.Secondary, c: Ctor.Secondary) =>
              p.copy(zLoop(p.mods, c.mods, loop[Mod]), loop(p.name, c.name), zzLoop(p.paramss, c.paramss, loop[Term.Param]), loop(p.body, c.body))

            case (p: Source, c: Source) =>
              p.copy(zLoop(p.stats, c.stats, loop[Stat]))

          }).appendScratchpad(cTree.scratchpad).asInstanceOf[T]
        }
        loop(parsedTree, convertedTree)
      }

      override def apply(unit: CompilationUnit) {
        val parsedTree = unit.source.content.mkString("").parse[Source].asInstanceOf[api.Source]
        val convertedTree = c.toMtree(unit.body, classOf[Source]).asInstanceOf[api.Source]

        // TODO: remove
        val mergedTree = merge(parsedTree, convertedTree)
        println(mergedTree.show[Semantics])
        println("======================================================")
        println(convertedTree.show[Semantics])

        unit.body.appendMetadata("scalameta" -> merge(parsedTree, convertedTree))

      }

    }
  }
}