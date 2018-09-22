package models

import javax.inject.Inject
import play.api.db.slick.DatabaseConfigProvider
import play.api.Configuration
import clickhouse.ClickHouseProfile
import com.sksamuel.elastic4s.ElasticsearchClientUri
import models.Entities._
import models.Functions._
import models.Entities.DBImplicits._
import models.Entities.ESImplicits._
import models.Violations.{InputParameterCheckError, SearchStringViolation}
import sangria.validation.Violation

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.concurrent._
import com.sksamuel.elastic4s.http._
import play.db.NamedDatabase

class Backend @Inject()(@NamedDatabase("default") protected val dbConfigProvider: DatabaseConfigProvider,
                        @NamedDatabase("sumstats") protected val dbConfigProviderSumStats: DatabaseConfigProvider, config: Configuration){
  val dbConfig = dbConfigProvider.get[ClickHouseProfile]
  val dbConfigSumStats = dbConfigProviderSumStats.get[ClickHouseProfile]
  val db = dbConfig.db
  val dbSS = dbConfigSumStats.db

  import dbConfig.profile.api._

  // you must import the DSL to use the syntax helpers
  import com.sksamuel.elastic4s.http.ElasticDsl._
  val esUri = ElasticsearchClientUri(config.get[String]("ot.elasticsearch.host"),
    config.get[Int]("ot.elasticsearch.port"))

  def buildPheWASTable(variantID: String, pageIndex: Option[Int], pageSize: Option[Int]):
  Future[Entities.PheWASTable] = {
    val limitClause = parsePaginationTokens(pageIndex, pageSize)
    val variant = Variant(variantID)

    variant match {
      case Right(v) =>
        val segment = toSumStatsSegment(v.locus.position)
        val tableName = gwasSumStatsTName format v.locus.chrId
        val query =
          sql"""
               |select
               | study_id,
               | pval,
               | beta,
               | se,
               | eaf,
               | maf,
               | n_samples_variant_level,
               | n_samples_study_level,
               | n_cases_study_level,
               | n_cases_variant_level,
               | if(is_cc,exp(beta),NULL) as odds_ratio,
               | chip,
               | info
               |from #$tableName
               |prewhere chrom = ${v.locus.chrId} and
               |  pos_b37 = ${v.locus.position} and
               |  segment = $segment and
               |  variant_id_b37 = ${v.id}
               |#$limitClause
         """.stripMargin.as[VariantPheWAS]

        dbSS.run(query.asTry).map {
          case Success(traitVector) => PheWASTable(traitVector)
          case Failure(_) => PheWASTable(associations = Vector.empty)
        }

      case Left(violation) => Future.failed(InputParameterCheckError(Vector(violation)))
    }
  }

  def getG2VSchema: Future[Entities.G2VSchema] = {
    def toSeqStruct(elems: Map[String, Map[String, String]]) = {
      (for {
        triple <- elems
        tuple <- triple._2

      } yield Entities.G2VSchemaElement(triple._1, tuple._1,
        StrSeqRep(tuple._2).rep.map(el => Tissue(el)))).toSeq
    }
    val studyQ = sql"""
                      |select
                      | type_id,
                      | source_id,
                      | feature_set
                      |from #$v2gStructureTName
      """.stripMargin.as[(String, String, String)]

    db.run(studyQ.asTry).map {
      case Success(v) =>
        val mappedRows = v.groupBy(_._1).mapValues(_.groupBy(_._2).mapValues(_.head._3))
        val qtlElems = toSeqStruct(mappedRows.filterKeys(defaultQtlTypes.contains(_)))
        val intervalElems = toSeqStruct(mappedRows.filterKeys(defaultIntervalTypes.contains(_)))
        val fpredElems = toSeqStruct(mappedRows.filterKeys(defaultFPredTypes.contains(_)))

        G2VSchema(qtlElems, intervalElems, fpredElems)
      case Failure(ex) => println(ex)
        G2VSchema(Seq.empty, Seq.empty, Seq.empty)
    }
  }

  def getSearchResultSet(qString: String, pageIndex: Option[Int], pageSize: Option[Int]):
  Future[Entities.SearchResultSet] = {
    val limitClause = parsePaginationTokensForES(pageIndex, pageSize)
    val stoken = qString.toLowerCase
    val cleanedTokens = stoken.replaceAll("-", " and ")

    if (stoken.length > 0) {
      val esQ = HttpClient(esUri)
      esQ.execute {
          search("studies") query boolQuery.should(prefixQuery("study_id", stoken),
            prefixQuery("pmid", stoken),
            queryStringQuery(cleanedTokens)) start limitClause._1 limit limitClause._2
      }.zip {
        esQ.execute {
          search("variant_*") query boolQuery.should(prefixQuery("variant_id", stoken),
            prefixQuery("rs_id", stoken)) start limitClause._1 limit limitClause._2
        }
      }.zip {
        esQ.execute {
          search("genes") query boolQuery.should(prefixQuery("gene_id", stoken),
            queryStringQuery(cleanedTokens)) start limitClause._1 limit limitClause._2
        }
      }.map{
        case ((studiesRS, variantsRS), genesRS) =>
          SearchResultSet(genesRS.totalHits, genesRS.to[Gene],
            variantsRS.totalHits, variantsRS.to[VariantSearchResult],
            studiesRS.totalHits, studiesRS.to[Study])
      }
    } else {
      Future.failed(InputParameterCheckError(Vector(SearchStringViolation())))
    }
  }

  def getStudies(stids: Seq[String]): Future[Vector[Entities.Study]] = {
    val stidListString = stids.map("'" + _ + "'").mkString(",")
    val studiesSQL = sql"""
                      |select
                      | study_id,
                      | trait_code,
                      | trait_reported,
                      | trait_efos,
                      | pmid,
                      | pub_date,
                      | pub_journal,
                      | pub_title,
                      | pub_author,
                      | ancestry_initial,
                      | ancestry_replication,
                      | n_initial,
                      | n_replication,
                      | n_cases,
                      | trait_category
                      |from #$studiesTName
                      |where study_id in (#${stidListString})
      """.stripMargin.as[Study]

    db.run(studiesSQL.asTry).map {
      case Success(v) => v
      case Failure(_) => Vector.empty
    }
  }

  def buildManhattanTable(studyID: String, pageIndex: Option[Int], pageSize: Option[Int]):
  Future[Entities.ManhattanTable] = {
    val limitClause = parsePaginationTokens(pageIndex, pageSize)

    val idxVariants = sql"""
      |SELECT
      |    index_variant_id,
      |    index_rs_id,
      |    pval,
      |    credibleSetSize,
      |    ldSetSize,
      |    uniq_variants,
      |    top_genes_ids,
      |    top_genes_names,
      |    top_genes_scores
      |FROM
      |(
      |    SELECT
      |        index_variant_id,
      |        any(index_rs_id) AS index_rs_id,
      |        any(pval) AS pval,
      |        uniqIf(variant_id, posterior_prob > 0) AS credibleSetSize,
      |        uniqIf(variant_id, r2 > 0) AS ldSetSize,
      |        uniq(variant_id) AS uniq_variants
      |    FROM #$v2dByStTName
      |    PREWHERE stid = $studyID
      |    GROUP BY index_variant_id
      |)
      |ALL LEFT OUTER JOIN
      |(
      |    SELECT
      |        variant_id AS index_variant_id,
      |        groupArray(gene_id) AS top_genes_ids,
      |        groupArray(dictGetString('gene','gene_name',tuple(gene_id))) AS top_genes_names,
      |        groupArray(overall_score) AS top_genes_scores
      |    FROM ot.d2v2g_score_by_overall
      |    PREWHERE (variant_id = index_variant_id) AND (overall_score > 0.)
      |    GROUP BY variant_id
      |) USING (index_variant_id)
      |#$limitClause
      """.stripMargin.as[V2DByStudy]

    // map to proper manhattan association with needed fields
    db.run(idxVariants.asTry).map {
      case Success(v) => ManhattanTable(
        v.map(el => {
          // we got the line so correct variant must exist
          val variant = Variant(el.index_variant_id)
          val completedV = variant.map(v => Variant(v.locus, v.refAllele, v.altAllele, el.index_rs_id))

          ManhattanAssociation(completedV.right.get, el.pval, el.topGenes,
            el.credibleSetSize, el.ldSetSize, el.totalSetSize)
        })
      )
      case Failure(_) => ManhattanTable(associations = Vector.empty)
    }
  }

  def buildIndexVariantAssocTable(variantID: String, pageIndex: Option[Int], pageSize: Option[Int]):
  Future[Entities.IndexVariantTable] = {
    val limitClause = parsePaginationTokens(pageIndex, pageSize)
    val variant = Variant(variantID)

    variant match {
      case Right(v) =>
        val assocs = sql"""
                       |select
                       | variant_id,
                       | rs_id,
                       | stid,
                       | pval,
                       | ifNull(n_initial,0) + ifNull(n_replication,0),
                       | ifNull(n_cases, 0),
                       | r2,
                       | afr_1000g_prop,
                       | amr_1000g_prop,
                       | eas_1000g_prop,
                       | eur_1000g_prop,
                       | sas_1000g_prop,
                       | log10_abf,
                       | posterior_prob
                       |from #$v2dByChrPosTName
                       |prewhere
                       |  chr_id = ${v.locus.chrId} and
                       |  index_position = ${v.locus.position} and
                       |  index_ref_allele = ${v.refAllele} and
                       |  index_alt_allele = ${v.altAllele}
                       |#$limitClause
          """.stripMargin.as[IndexVariantAssociation]

        db.run(assocs.asTry).map {
          case Success(r) => Entities.IndexVariantTable(r)
          case Failure(_) => Entities.IndexVariantTable(associations = Vector.empty)
        }
      // case Failure(_) => Future.successful(Entities.IndexVariantTable(associations = Vector.empty))
      case Left(violation) =>
        Future.failed(InputParameterCheckError(Vector(violation)))
    }
  }

  def buildTagVariantAssocTable(variantID: String, pageIndex: Option[Int], pageSize: Option[Int]):
  Future[TagVariantTable] = {
    val limitClause = parsePaginationTokens(pageIndex, pageSize)
    val variant = Variant(variantID)

    variant match {
      case Right(v) =>
        val assocs = sql"""
                          |select
                          | index_variant_id,
                          | index_rs_id,
                          | stid,
                          | pval,
                          | ifNull(n_initial,0) + ifNull(n_replication,0),
                          | ifNull(n_cases, 0),
                          | r2,
                          | afr_1000g_prop,
                          | amr_1000g_prop,
                          | eas_1000g_prop,
                          | eur_1000g_prop,
                          | sas_1000g_prop,
                          | log10_abf,
                          | posterior_prob
                          |from #$v2dByChrPosTName
                          |prewhere
                          |  chr_id = ${v.locus.chrId} and
                          |  position = ${v.locus.position} and
                          |  ref_allele = ${v.refAllele} and
                          |  alt_allele = ${v.altAllele}
                          |#$limitClause
          """.stripMargin.as[TagVariantAssociation]

        // map to proper manhattan association with needed fields
        db.run(assocs.asTry).map {
          case Success(r) => Entities.TagVariantTable(r)
          case Failure(_) => Entities.TagVariantTable(associations = Vector.empty)
        }
      // case Failure(_) => Future.successful(Entities.TagVariantTable(associations = Vector.empty))
      case Left(violation) =>
        Future.failed(InputParameterCheckError(Vector(violation)))
    }
  }

  def buildGecko(chromosome: String, posStart: Long, posEnd: Long): Future[Option[Entities.Gecko]] = {
    (parseChromosome(chromosome), parseRegion(posStart, posEnd)) match {
      case (Right(chr), Right((start, end))) =>
        val assocs = sql"""
                          |select
                          |  variant_id,
                          |  rs_id ,
                          |  index_variant_id ,
                          |  index_variant_rsid ,
                          |  gene_id ,
                          |  dictGetString('gene','gene_name',tuple(gene_id)) as gene_name,
                          |  dictGetString('gene','biotype',tuple(gene_id)) as gene_type,
                          |  dictGetString('gene','chr',tuple(gene_id)) as gene_chr,
                          |  dictGetUInt32('gene','tss',tuple(gene_id)) as gene_tss,
                          |  dictGetUInt32('gene','start',tuple(gene_id)) as gene_start,
                          |  dictGetUInt32('gene','end',tuple(gene_id)) as gene_end,
                          |  dictGetUInt8('gene','fwdstrand',tuple(gene_id)) as gene_fwd,
                          |  cast(dictGetString('gene','exons',tuple(gene_id)), 'Array(UInt32)') as gene_exons,
                          |  stid,
                          |  r2,
                          |  posterior_prob ,
                          |  pval,
                          |  overall_score
                          |from (
                          | select
                          |  stid,
                          |  variant_id,
                          |  any(rs_id) as rs_id,
                          |  index_variant_id,
                          |  any(index_variant_rsid) as index_variant_rsid,
                          |  gene_id,
                          |  any(r2) as r2,
                          |  any(posterior_prob) as posterior_prob,
                          |  any(pval) as pval
                          | from #$d2v2gTName
                          | prewhere
                          |   chr_id = $chr and (
                          |   (position >= $start and position <= $end) or
                          |   (index_position >= $start and index_position <= $end) or
                          |   (dictGetUInt32('gene','start',tuple(gene_id)) >= $start and
                          |     dictGetUInt32('gene','start',tuple(gene_id)) <= $end) or
                          |   (dictGetUInt32('gene','end',tuple(gene_id)) >= $start and
                          |     dictGetUInt32('gene','end',tuple(gene_id)) <= $end))
                          | group by stid, index_variant_id, variant_id, gene_id
                          |) all inner join (
                          | select
                          |   variant_id,
                          |   gene_id,
                          |   overall_score
                          | from #$d2v2gOScoresTName
                          | prewhere chr_id = $chr and
                          | overall_score > 0.
                          |) using (variant_id, gene_id)
          """.stripMargin.as[GeckoLine]

        db.run(assocs.asTry).map {
          case Success(r) => Entities.Gecko(r.view)
          case Failure(_) => Entities.Gecko(Seq.empty)
        }
      case (chrEither, rangeEither) =>
        Future.failed(InputParameterCheckError(
          Vector(chrEither, rangeEither).filter(_.isLeft).map(_.left.get).asInstanceOf[Vector[Violation]]))
    }
  }

  def buildG2V(variantID: String): Future[Seq[Entities.G2VAssociation]] = {
    val variant = Variant(variantID)

    variant match {
      case Right(v) =>
        val assocs = sql"""
                          |SELECT
                          |    gene_id,
                          |    dictGetString('gene','gene_name',tuple(gene_id)) as gene_name,
                          |    dictGetString('gene','biotype',tuple(gene_id)) as gene_type,
                          |    dictGetString('gene','chr',tuple(gene_id)) as gene_chr,
                          |    dictGetUInt32('gene','tss',tuple(gene_id)) as gene_tss,
                          |    dictGetUInt32('gene','start',tuple(gene_id)) as gene_start,
                          |    dictGetUInt32('gene','end',tuple(gene_id)) as gene_end,
                          |    dictGetUInt8('gene','fwdstrand',tuple(gene_id)) as gene_fwd,
                          |    cast(dictGetString('gene','exons',tuple(gene_id)), 'Array(UInt32)') as gene_exons,
                          |    overall_score,
                          |    source_list,
                          |    source_score_list,
                          |    type_id,
                          |    source_id,
                          |    feature,
                          |    fpred_max_label,
                          |    fpred_max_score,
                          |    qtl_beta,
                          |    qtl_se,
                          |    qtl_pval,
                          |    interval_score,
                          |    qtl_score_q,
                          |    interval_score_q
                          |FROM
                          |(
                          |    SELECT
                          |        gene_id,
                          |        type_id,
                          |        source_id,
                          |        feature,
                          |        fpred_max_label,
                          |        fpred_max_score,
                          |        qtl_beta,
                          |        qtl_se,
                          |        qtl_pval,
                          |        interval_score,
                          |        qtl_score_q,
                          |        interval_score_q
                          |    FROM #$v2gTName
                          |    PREWHERE
                          |       (chr_id = ${v.locus.chrId}) AND
                          |       (position = ${v.locus.position}) AND
                          |       (variant_id = ${v.id})
                          |)
                          |ALL INNER JOIN
                          |(
                          |    SELECT
                          |        variant_id,
                          |        gene_id,
                          |        source_list,
                          |        source_score_list,
                          |        overall_score
                          |    FROM #$v2gOScoresTName
                          |    PREWHERE (chr_id = ${v.locus.chrId}) AND
                          |       (variant_id = ${v.id})
                          |) USING (gene_id)
                          |ORDER BY gene_id ASC
          """.stripMargin.as[ScoredG2VLine]

        db.run(assocs.asTry).map {
          case Success(r) => r.view.groupBy(_.gene.id).mapValues(G2VAssociation(_)).values.toSeq
          case Failure(_) => Seq.empty
        }
      case Left(violation) => Future.failed(InputParameterCheckError(Vector(violation)))
    }
  }

  private val v2dByStTName: String = "v2d_by_stchr"
  private val v2dByChrPosTName: String = "v2d_by_chrpos"
  private val d2v2gTName: String = "d2v2g"
  private val d2v2gOScoresTName: String = "d2v2g_score_by_overall"
  private val v2gTName: String = "v2g"
  private val v2gOScoresTName: String = "v2g_score_by_overall"
  private val v2gStructureTName: String = "v2g_structure"
  private val studiesTName: String = "studies"
  private val gwasSumStatsTName: String = "gwas_chr_%s"
}