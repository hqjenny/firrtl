// See LICENSE for license details.

package firrtl.passes

import com.typesafe.scalalogging.LazyLogging

import firrtl._
import firrtl.ir._
import firrtl.Utils._
import firrtl.Mappers._
import firrtl.PrimOps._

import scala.collection.mutable

trait Pass extends LazyLogging {
  def name: String
  def run(c: Circuit): Circuit
}

// Error handling
class PassException(message: String) extends Exception(message)
class PassExceptions(exceptions: Seq[PassException]) extends Exception("\n" + exceptions.mkString("\n"))
class Errors {
  val errors = collection.mutable.ArrayBuffer[PassException]()
  def append(pe: PassException) = errors.append(pe)
  def trigger() = errors.size match {
    case 0 =>
    case 1 => throw errors.head
    case _ =>
      append(new PassException(s"${errors.length} errors detected!"))
      throw new PassExceptions(errors)
  }
}

// These should be distributed into separate files
object ToWorkingIR extends Pass {
  def name = "Working IR"

  def toExp(e: Expression): Expression = e map toExp match {
    case ex: Reference => WRef(ex.name, ex.tpe, NodeKind, UNKNOWNGENDER)
    case ex: SubField => WSubField(ex.expr, ex.name, ex.tpe, UNKNOWNGENDER)
    case ex: SubIndex => WSubIndex(ex.expr, ex.value, ex.tpe, UNKNOWNGENDER)
    case ex: SubAccess => WSubAccess(ex.expr, ex.index, ex.tpe, UNKNOWNGENDER)
    case ex => ex // This might look like a case to use case _ => e, DO NOT!
  }

  def toStmt(s: Statement): Statement = s map toExp match {
    case sx: DefInstance => WDefInstance(sx.info, sx.name, sx.module, UnknownType)
    case sx => sx map toStmt
  }

  def run (c:Circuit): Circuit =
    c copy (modules = c.modules map (_ map toStmt))
}

object PullMuxes extends Pass {
   def name = "Pull Muxes"
   def run(c: Circuit): Circuit = {
     def pull_muxes_e(e: Expression): Expression = e map pull_muxes_e match {
       case ex: WSubField => ex.exp match {
         case exx: Mux => Mux(exx.cond,
           WSubField(exx.tval, ex.name, ex.tpe, ex.gender),
           WSubField(exx.fval, ex.name, ex.tpe, ex.gender), ex.tpe)
         case exx: ValidIf => ValidIf(exx.cond,
           WSubField(exx.value, ex.name, ex.tpe, ex.gender), ex.tpe)
         case _ => ex  // case exx => exx causes failed tests
       }
       case ex: WSubIndex => ex.exp match {
         case exx: Mux => Mux(exx.cond,
           WSubIndex(exx.tval, ex.value, ex.tpe, ex.gender),
           WSubIndex(exx.fval, ex.value, ex.tpe, ex.gender), ex.tpe)
         case exx: ValidIf => ValidIf(exx.cond,
           WSubIndex(exx.value, ex.value, ex.tpe, ex.gender), ex.tpe)
         case _ => ex  // case exx => exx causes failed tests
       }
       case ex: WSubAccess => ex.exp match {
         case exx: Mux => Mux(exx.cond,
           WSubAccess(exx.tval, ex.index, ex.tpe, ex.gender),
           WSubAccess(exx.fval, ex.index, ex.tpe, ex.gender), ex.tpe)
         case exx: ValidIf => ValidIf(exx.cond,
           WSubAccess(exx.value, ex.index, ex.tpe, ex.gender), ex.tpe)
         case _ => ex  // case exx => exx causes failed tests
       }
       case ex => ex
     }
     def pull_muxes(s: Statement): Statement = s map pull_muxes map pull_muxes_e
     val modulesx = c.modules.map {
       case (m:Module) => Module(m.info, m.name, m.ports, pull_muxes(m.body))
       case (m:ExtModule) => m
     }
     Circuit(c.info, modulesx, c.main)
   }
}

object ExpandConnects extends Pass {
  def name = "Expand Connects"
  def run(c: Circuit): Circuit = {
    def expand_connects(m: Module): Module = {
      val genders = collection.mutable.LinkedHashMap[String,Gender]()
      def expand_s(s: Statement): Statement = {
        def set_gender(e: Expression): Expression = e map set_gender match {
          case ex: WRef => WRef(ex.name, ex.tpe, ex.kind, genders(ex.name))
          case ex: WSubField =>
            val f = get_field(ex.exp.tpe, ex.name)
            val genderx = times(gender(ex.exp), f.flip)
            WSubField(ex.exp, ex.name, ex.tpe, genderx)
          case ex: WSubIndex => WSubIndex(ex.exp, ex.value, ex.tpe, gender(ex.exp))
          case ex: WSubAccess => WSubAccess(ex.exp, ex.index, ex.tpe, gender(ex.exp))
          case ex => ex
        }
        s match {
          case sx: DefWire => genders(sx.name) = BIGENDER; sx
          case sx: DefRegister => genders(sx.name) = BIGENDER; sx
          case sx: WDefInstance => genders(sx.name) = MALE; sx
          case sx: DefMemory => genders(sx.name) = MALE; sx
          case sx: DefNode => genders(sx.name) = MALE; sx
          case sx: IsInvalid =>
            val invalids = (create_exps(sx.expr) foldLeft Seq[Statement]())(
               (invalids,  expx) => gender(set_gender(expx)) match {
                  case BIGENDER => invalids :+ IsInvalid(sx.info, expx)
                  case FEMALE => invalids :+ IsInvalid(sx.info, expx)
                  case _ => invalids
               }
            )
            invalids.size match {
               case 0 => EmptyStmt
               case 1 => invalids.head
               case _ => Block(invalids)
            }
          case sx: Connect =>
            val locs = create_exps(sx.loc)
            val exps = create_exps(sx.expr)
            Block((locs zip exps).zipWithIndex map {case ((locx, expx), i) =>
               get_flip(sx.loc.tpe, i, Default) match {
                  case Default => Connect(sx.info, locx, expx)
                  case Flip => Connect(sx.info, expx, locx)
               }
            })
          case sx: PartialConnect =>
            val ls = get_valid_points(sx.loc.tpe, sx.expr.tpe, Default, Default)
            val locs = create_exps(sx.loc)
            val exps = create_exps(sx.expr)
            val stmts = ls map { case (x, y) =>
              locs(x).tpe match {
                case AnalogType(_) => Attach(sx.info, Seq(locs(x), exps(y)))
                case _ =>
                  get_flip(sx.loc.tpe, x, Default) match {
                    case Default => Connect(sx.info, locs(x), exps(y))
                    case Flip => Connect(sx.info, exps(y), locs(x))
                  }
              }
            }
            Block(stmts)
          case sx => sx map expand_s
        }
      }

      m.ports.foreach { p => genders(p.name) = to_gender(p.direction) }
      Module(m.info, m.name, m.ports, expand_s(m.body))
    }

    val modulesx = c.modules.map {
       case (m: ExtModule) => m
       case (m: Module) => expand_connects(m)
    }
    Circuit(c.info, modulesx, c.main)
  }
}


// Replace shr by amount >= arg width with 0 for UInts and MSB for SInts
// TODO replace UInt with zero-width wire instead
object Legalize extends Pass {
  def name = "Legalize"
  private def legalizeShiftRight(e: DoPrim): Expression = {
    require(e.op == Shr)
    val amount = e.consts.head.toInt
    val width = bitWidth(e.args.head.tpe)
    lazy val msb = width - 1
    if (amount >= width) {
      e.tpe match {
        case UIntType(_) => zero
        case SIntType(_) =>
          val bits = DoPrim(Bits, e.args, Seq(msb, msb), BoolType)
          DoPrim(AsSInt, Seq(bits), Seq.empty, SIntType(IntWidth(1)))
        case t => error(s"Unsupported type $t for Primop Shift Right")
      }
    } else {
      e
    }
  }
  private def legalizeBits(expr: DoPrim): Expression = {
    lazy val (hi, low) = (expr.consts.head, expr.consts(1))
    lazy val mask = (BigInt(1) << (hi - low + 1).toInt) - 1
    lazy val width = IntWidth(hi - low + 1)
    expr.args.head match {
      case UIntLiteral(value, _) => UIntLiteral((value >> low.toInt) & mask, width)
      case SIntLiteral(value, _) => SIntLiteral((value >> low.toInt) & mask, width)
      //case FixedLiteral
      case _ => expr
    }
  }
  private def legalizePad(expr: DoPrim): Expression = expr.args.head match {
    case UIntLiteral(value, IntWidth(width)) if width < expr.consts.head =>
      UIntLiteral(value, IntWidth(expr.consts.head))
    case SIntLiteral(value, IntWidth(width)) if width < expr.consts.head =>
      SIntLiteral(value, IntWidth(expr.consts.head))
    case _ => expr
  }
  private def legalizeConnect(c: Connect): Statement = {
    val t = c.loc.tpe
    val w = bitWidth(t)
    if (w >= bitWidth(c.expr.tpe)) {
      c
    } else {
      val bits = DoPrim(Bits, Seq(c.expr), Seq(w - 1, 0), UIntType(IntWidth(w)))
      val expr = t match {
        case UIntType(_) => bits
        case SIntType(_) => DoPrim(AsSInt, Seq(bits), Seq(), SIntType(IntWidth(w)))
        case FixedType(_, IntWidth(p)) => DoPrim(AsFixedPoint, Seq(bits), Seq(p), t)
      }
      Connect(c.info, c.loc, expr)
    }
  }
  def run (c: Circuit): Circuit = {
    def legalizeE(expr: Expression): Expression = expr map legalizeE match {
      case prim: DoPrim => prim.op match {
        case Shr => legalizeShiftRight(prim)
        case Pad => legalizePad(prim)
        case Bits => legalizeBits(prim)
        case _ => prim
      }
      case e => e // respect pre-order traversal
    }
    def legalizeS (s: Statement): Statement = {
      val legalizedStmt = s match {
        case c: Connect => legalizeConnect(c)
        case _ => s
      }
      legalizedStmt map legalizeS map legalizeE
    }
    c copy (modules = c.modules map (_ map legalizeS))
  }
}

object VerilogWrap extends Pass {
  def name = "Verilog Wrap"
  def vWrapE(e: Expression): Expression = e map vWrapE match {
    case e: DoPrim => e.op match {
      case Tail => e.args.head match {
        case e0: DoPrim => e0.op match {
          case Add => DoPrim(Addw, e0.args, Nil, e.tpe)
          case Sub => DoPrim(Subw, e0.args, Nil, e.tpe)
          case _ => e
        }
        case _ => e
      }
      case _ => e
    }
    case _ => e
  }
  def vWrapS(s: Statement): Statement = {
    s map vWrapS map vWrapE match {
      case sx: Print => sx copy (string = VerilogStringLitHandler.format(sx.string))
      case sx => sx
    }
  }

  def run(c: Circuit): Circuit =
    c copy (modules = c.modules map (_ map vWrapS))
}

object VerilogRename extends Pass {
  def name = "Verilog Rename"
  def verilogRenameN(n: String): String =
    if (v_keywords(n)) "%s$".format(n) else n

  def verilogRenameE(e: Expression): Expression = e match {
    case ex: WRef => ex copy (name = verilogRenameN(ex.name))
    case ex => ex map verilogRenameE
  }

  def verilogRenameS(s: Statement): Statement =
    s map verilogRenameS map verilogRenameE map verilogRenameN

  def verilogRenameP(p: Port): Port =
    p copy (name = verilogRenameN(p.name))

  def run(c: Circuit): Circuit =
    c copy (modules = c.modules map (_ map verilogRenameP map verilogRenameS))
}

/** Makes changes to the Firrtl AST to make Verilog emission easier
  *
  * - For each instance, adds wires to connect to each port
  *   - Note that no Namespace is required because Uniquify ensures that there will be no
  *     collisions with the lowered names of instance ports
  * - Also removes Attaches where a single Port OR Wire connects to 1 or more instance ports
  *   - These are expressed in the portCons of WDefInstConnectors
  *
  * @note The result of this pass is NOT legal Firrtl
  */
object VerilogPrep extends Pass {
  def name = "Verilog Prep"

  type AttachSourceMap = Map[WrappedExpression, Expression]

  // Finds attaches with only a single source (Port or Wire)
  //   - Creates a map of attached expressions to their source
  //   - Removes the Attach
  private def collectAndRemoveAttach(m: DefModule): (DefModule, AttachSourceMap) = {
    val sourceMap = mutable.HashMap.empty[WrappedExpression, Expression]
    lazy val namespace = Namespace(m)

    def onStmt(stmt: Statement): Statement = stmt map onStmt match {
      case attach: Attach =>
        val wires = attach.exprs groupBy kind
        val sources = wires.getOrElse(PortKind, Seq.empty) ++ wires.getOrElse(WireKind, Seq.empty)
        val instPorts = wires.getOrElse(InstanceKind, Seq.empty)
        // Sanity check (Should be caught by CheckTypes)
        assert(sources.size + instPorts.size == attach.exprs.size)

        sources match {
          case Seq() => // Zero sources, can add a wire to connect and remove
            val name = namespace.newTemp
            val wire = DefWire(NoInfo, name, instPorts.head.tpe)
            val ref = WRef(wire)
            for (inst <- instPorts) sourceMap(inst) = ref
            wire // Replace the attach with new source wire definition
          case Seq(source) => // One source can be removed
            assert(!sourceMap.contains(source)) // should have been merged
            for (inst <- instPorts) sourceMap(inst) = source
            EmptyStmt
          case moreThanOne =>
            attach
        }
      case s => s
    }

    (m map onStmt, sourceMap.toMap)
  }

  def run(c: Circuit): Circuit = {
    def lowerE(e: Expression): Expression = e match {
      case (_: WRef | _: WSubField) if kind(e) == InstanceKind =>
        WRef(LowerTypes.loweredName(e), e.tpe, kind(e), gender(e))
      case _ => e map lowerE
    }

    def lowerS(attachMap: AttachSourceMap)(s: Statement): Statement = s match {
      case WDefInstance(info, name, module, tpe) =>
        val portRefs = create_exps(WRef(name, tpe, ExpKind, MALE))
        val (portCons, wires) = portRefs.map { p =>
          attachMap.get(p) match {
            // If it has a source in attachMap use that
            case Some(ref) => (p -> ref, None)
            // If no source, create a wire corresponding to the port and connect it up
            case None =>
              val wire = DefWire(info, LowerTypes.loweredName(p), p.tpe)
              (p -> WRef(wire), Some(wire))
          }
        }.unzip
        val newInst = WDefInstanceConnector(info, name, module, tpe, portCons)
        Block(wires.flatten :+ newInst)
      case other => other map lowerS(attachMap) map lowerE
    }

    val modulesx = c.modules map { mod =>
      val (modx, attachMap) = collectAndRemoveAttach(mod)
      modx map lowerS(attachMap)
    }
    c.copy(modules = modulesx)
  }
}
