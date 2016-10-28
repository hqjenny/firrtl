/*
Copyright (c) 2014 - 2016 The Regents of the University of
California (Regents). All Rights Reserved.  Redistribution and use in
source and binary forms, with or without modification, are permitted
provided that the following conditions are met:
   * Redistributions of source code must retain the above
     copyright notice, this list of conditions and the following
     two paragraphs of disclaimer.
   * Redistributions in binary form must reproduce the above
     copyright notice, this list of conditions and the following
     two paragraphs of disclaimer in the documentation and/or other materials
     provided with the distribution.
   * Neither the name of the Regents nor the names of its contributors
     may be used to endorse or promote products derived from this
     software without specific prior written permission.
IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
MODIFICATIONS.
*/

package firrtl

import com.typesafe.scalalogging.LazyLogging
import scala.collection.mutable
import java.io.Writer
import Annotations._

import firrtl.ir.Circuit
/**
 * RenameMap maps old names to modified names.  Generated by transformations
 * that modify names
 */
case class RenameMap(map: Map[Named, Seq[Named]])

// ===========================================
//                 Transforms
// -------------------------------------------

case class TransformResult(
  circuit: Circuit,
  renames: Option[RenameMap] = None,
  annotation: Option[AnnotationMap] = None)

// - Transforms a circuit
// - Can consume multiple CircuitAnnotation's
trait Transform {
  def execute(circuit: Circuit, annotationMap: AnnotationMap): TransformResult
}


// ===========================================
//                 Compilers
// -------------------------------------------

case class CompilerResult(circuit: Circuit, annotationMap: AnnotationMap)

// - A sequence of transformations
// - Call compile to executes each transformation in sequence onto
//    a given circuit.
trait Compiler {
  def transforms(w: Writer): Seq[Transform]
  def compile(circuit: Circuit, annotationMap: AnnotationMap, writer: Writer): CompilerResult =
    (transforms(writer) foldLeft CompilerResult(circuit, annotationMap)){ (in, xform) =>
      val result = xform.execute(in.circuit, in.annotationMap)
      val remappedAnnotations: Seq[Annotation] = Nil
      //result.renames match {
      //  case Some(RenameMap(rmap)) =>
      //    // For each key in the rename map (rmap), obtain the
      //    // corresponding annotations (in.annotationMap.get(from)). If any
      //    // annotations exist, for each annotation, create a sequence of
      //    // annotations with the names in rmap's value.
      //    for {
      //      (oldName, newNames) <- rmap.toSeq
      //      tID2OldAnnos <- in.annotationMap.get(oldName).toSeq
      //      oldAnno <- tID2OldAnnos.values
      //      newAnno <- oldAnno.update(newNames)
      //    } yield newAnno
      //  case _ => in.annotationMap.annotations
      //}
      val resultAnnotations: Seq[Annotation] = result.annotation match {
        case None => Nil
        case Some(p) => p.annotations
      }
      CompilerResult(result.circuit,
        new AnnotationMap(remappedAnnotations ++ resultAnnotations))
    }
}

