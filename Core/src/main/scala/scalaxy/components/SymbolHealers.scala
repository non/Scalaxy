package scalaxy ; package plugin
//import common._
import pluginBase._
import components._

import scala.tools.nsc.Global

import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.typechecker.Analyzer
import scala.tools.nsc.typechecker.Contexts
import scala.tools.nsc.typechecker.Modes
import scala.Predef._

trait SymbolHealers
extends TypingTransformers
   with Modes
{
  this: PluginComponent =>

  import global._
  import global.definitions._
  import scala.tools.nsc.symtab.Flags._

  def healSymbols(unit: CompilationUnit, rootOwner: Symbol, root: Tree, expectedTpe: Type): Tree = {
    new Traverser {
      var syms = new collection.mutable.HashMap[Name, Symbol]()
      currentOwner = rootOwner
      
      def subSyms[V](v: => V): V = {
        val oldSyms = syms
        syms = new collection.mutable.HashMap[Name, Symbol]()
        syms ++= oldSyms
        try {
          v
        } finally {
          syms = oldSyms
        }
      }
      override def traverse(tree: Tree) = {
        try {
          def traverseValDef(vd: ValDef) = {
            val ValDef(mods, name, tpt, rhs) = vd
            val sym = (
              if (mods.hasFlag(MUTABLE))
                currentOwner.newVariable(name)
              else
                currentOwner.newValue(name)
            ).setFlag(mods.flags)
            
            tree.setSymbol(sym)
            
            syms(name) = sym
    
            atOwner(sym) {
              traverse(tpt)
              traverse(rhs)
            }
            
            typer.typed(rhs)
            
            var tpe = rhs.tpe
            if (tpe.isInstanceOf[ConstantType])
              tpe = tpe.widen
              
            sym.setInfo(Option(tpe).getOrElse(NoType))
          }
          
          tree match {
            case (_: Block) | (_: ClassDef) =>
              subSyms {
                super.traverse(tree)
              }
              
            case Function(vparams, body) =>
              val sym = currentOwner.newAnonymousFunctionValue(NoPosition)
              tree.setSymbol(sym)
              
              atOwner(sym) {
                subSyms {
                  vparams.foreach(traverseValDef _)
                  traverse(body)
                }
              }
              
            case Ident(n) =>
              if (tree.symbol == null || tree.symbol == NoSymbol || tree.symbol.owner.isNestedIn(rootOwner)) {
                for (s <- syms.get(n))
                  tree.setSymbol(s)
              }
              
            case vd: ValDef =>
              traverseValDef(vd)
              
            case _ =>
              super.traverse(tree)
          }
        } catch { case ex =>
          println("ERROR while assigning missing symbols to " + tree + " : " + ex)
          throw ex
        }
      }
    }.traverse(root)
    root
  }
}
