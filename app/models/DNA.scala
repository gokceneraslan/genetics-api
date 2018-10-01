package models

import clickhouse.rep.SeqRep.LSeqRep
import clickhouse.rep.SeqRep.Implicits._
import models.Violations.{GeneViolation, VariantViolation}
import sangria.execution.deferred.HasId
import sangria.schema.{Field, LongType, ObjectType, OptionType, StringType, fields}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import slick.jdbc.{GetResult, PositionedParameters, SQLActionBuilder, SetParameter}

object DNA {
  case class Locus(pos1: Position, pos2: Position)
  case class Loci(locus: Locus, restLocus: Locus*)

  case class Position(chrId: String, position: Long)

  case class Variant(position: Position, refAllele: String, altAllele: String, rsId: Option[String],
                     nearestGeneId: Option[String] = None, nearestCodingGeneId: Option[String] = None) {
    lazy val id: String = List(position.chrId, position.position.toString, refAllele, altAllele)
      .map(_.toUpperCase)
      .mkString("_")
  }

  object Variant {
    def apply(variantId: String, rsId: Option[String] = None): Either[VariantViolation, Variant] = {
      variantId.toUpperCase.split("_").toList.filter(_.nonEmpty) match {
        case List(chr: String, pos: String, ref: String, alt: String) =>
          Right(Variant(Position(chr, pos.toLong), ref, alt, rsId))
        case _ =>
          Left(VariantViolation(variantId))
      }
    }
  }

  case class Gene(id: String, symbol: Option[String], start: Option[Long] = None, end: Option[Long] = None,
                  chromosome: Option[String] = None, tss: Option[Long] = None,
                  bioType: Option[String] = None, fwd: Option[Boolean] = None, exons: Seq[Long] = Seq.empty)

  object Gene {
    def apply(geneId: String): Either[GeneViolation, Gene] = {
      geneId.toUpperCase.split("\\.").toList.filter(_.nonEmpty) match {
        case ensemblId :: xs =>
          Right(Gene(ensemblId, None))
        case Nil =>
          Left(GeneViolation(geneId))
      }
    }

    implicit val hasId = HasId[Gene, String](_.id)
  }

  object Implicits {
    implicit def stringToVariant(variantID: String): Either[VariantViolation, Variant] =
      Variant.apply(variantID)

    implicit val getVariantFromDB: GetResult[Variant] =
      GetResult(r => Variant(Position(r.nextString, r.nextLong), refAllele = r.nextString,
        altAllele = r.nextString, rsId = r.nextStringOption, nearestGeneId = r.nextStringOption,
        nearestCodingGeneId = r.nextStringOption))

    implicit val getGeneFromDB: GetResult[Gene] =
      GetResult(r => Gene(id = r.nextString(), symbol = r.nextStringOption(), bioType = r.nextStringOption(),
        chromosome = r.nextStringOption(), tss = r.nextLongOption(),
        start = r.nextLongOption(), end = r.nextLongOption(), fwd = r.nextBooleanOption(),
        exons = LSeqRep(r.nextString())))
  }
}
