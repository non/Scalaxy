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
import scala.reflect._

//import scala.tools.nsc.typechecker.Contexts._

object ReplacementsComponent {
  val runsAfter = List[String](
    "typer"
  )
  val runsBefore = List[String](
    "refchecks"
  )
  val phaseName = "scalaxy-rewriter"
}

class ReplacementsComponent(val global: Global, val options: PluginOptions, val replacementHolders: AnyRef*)
extends PluginComponent
   with Transform
   with TypingTransformers
   with Modes
   with Replacements
   with MirrorConversions
   with SymbolHealers
   with WithOptions
{
  import global._
  import global.definitions._
  import gen._
  import CODE._
  import scala.tools.nsc.symtab.Flags._
  import typer.typed
  import analyzer.{SearchResult, ImplicitSearch, UnTyper}

  override val runsAfter = ReplacementsComponent.runsAfter
  override val runsBefore = ReplacementsComponent.runsBefore
  override val phaseName = ReplacementsComponent.phaseName

  import ReplacementDefinitions._
  //import Replacements._
  
  case class ConvertedReplacement(pattern: Tree, replacement: Bindings => Tree)
  
  def mirrorNodeToString(tree: mirror.Tree) = {
    new mirror.Traverser {
      var indent = 0
      def ptind =
        for (i <- 0 until indent)
          print("\t")
      override def traverse(t: mirror.Tree) = {
        ptind
        //println(t.getClass.getName + " ( <- " + t.getSuperclass.getName + ")")
        println(t.getClass.getSimpleName + " // tpe = " + t.tpe + ", sym = " + t.symbol + ", sym.tpe = " + (if (Option(t.symbol).getOrElse(NoSymbol) == NoSymbol) "?" else t.symbol.asType))
        indent = indent + 1
        super.traverse(t)
        indent = indent - 1
      }
    }.traverse(tree)
  }
  val replacements = replacementHolders.filter(_ != null).flatMap(getReplacementDefinitions(_)).map { 
    case (n, r) =>
      //println("Converting pattern from mirror to global :")
      //val orig = r.pattern
      //println("\tmirror = " + orig)
      //println("\tmirror = " + mirrorNodeToString(orig))
      //eraseSymbols(orig)
      val conv = mirrorToGlobal(r.pattern, EmptyBindings)
      //println("\t  conv = " + conv)
      //println("\t  conv = " + nodeToString(conv))
      //println("\t  rep = " + r.replacement)
      //println("\t  rep = " + mirrorNodeToString(r.replacement))
      (n, ConvertedReplacement(conv, bindings => {
        val rep = mirrorToGlobal(r.replacement, bindings)
        //eraseSymbols(rep)
        rep
      }))
  } 
  
  def newTransformer(unit: CompilationUnit) = new TypingTransformer(unit) {  
    override def transform(tree: Tree): Tree = {
      val sup = super.transform(tree)
      var expanded = sup
      //println("got " + t)
  
      for ((n, r) <- replacements) {
        try {
          val bindings @ Bindings(nameBindings, typeBindings) = matchAndResolveBindings(r.pattern, expanded)
          println("Bindings for '" + n + "':\n\t" + (nameBindings ++ typeBindings).mkString("\n\t"))
          
          //val replacement = mirrorToGlobal(r.replacement)
          val replacement = r.replacement(bindings)
          //eraseTypesAndSymbols(replacement)
          //val rep = replace(replacement, bindings)
          println("Replacement '" + n + "':\n\t" + replacement.toString.replaceAll("\n", "\n\t"))
          expanded = replacement
        } catch { 
          case NoTreeMatchException(_, _, _) =>
          case NoTypeMatchException(expected, found, msg) =>
            //ex.printStackTrace
            //println("Replacement '" + n + "' failed at " + tree.pos + " : " + ex)
            
            println("ERROR: " + msg +
              " (\n\texpected = " + expected + ": " + Option(expected).map(_.getClass.getName) + 
              ",\n\tfound = " + found + ": " + Option(found).map(_.getClass.getName) + "\n)"
            )
        }
      }
      
      try {
        if (expanded eq sup) {
          sup
        } else {
          val expectedTpe = tree.tpe.dealias.deconst
          
          //println("expectedTpe = " + expectedTpe + ": " + expectedTpe.getClass.getName)
          //println("expanded = " + expanded)
          //println("expanded = " + nodeToString(expanded))
          val mode = EXPRmode
          
          val tpe = expanded.tpe
          //eraseTypes(expanded)
          //expanded.tpe = null
          expanded = healSymbols(unit, currentOwner, expanded, expectedTpe)
          expanded = typer.typed(expanded, mode, expectedTpe)
          /*
          
          expanded.tpe = NoType
          
          new Traverser { override def traverse(tree: Tree) = {
            try {
              super.traverse(tree)
            } catch { case ex => 
              println("Failed to traverse-type " + tree + " : " + ex)
            }
            typer.typed(tree)
          }}.traverse(expanded)
          
          //expanded.tpe = NoType
          expanded = typer.typed(expanded, mode, expectedTpe)
          //expanded = typer.typed(expanded)
          */
          if (expanded.tpe == null || expanded.tpe == NoType)
            expanded.tpe = tpe
          expanded
        }
      } catch { case ex =>
        ex.printStackTrace
        println("Error while trying to replace " + tree + " : " + ex)
        tree
      }
    }
  }
}
