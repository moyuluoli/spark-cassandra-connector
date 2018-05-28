/**
 * Copyright DataStax, Inc.
 *
 * Please see the included license file for details.
 */
package org.apache.spark.sql.cassandra

import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

import com.datastax.driver.core.SimpleStatement
import com.datastax.spark.connector.cql.{CassandraConnector, TableDef}
import com.datastax.spark.connector.util.Logging
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.spark.SparkConf
import org.apache.spark.sql.cassandra.SolrConstants._
import org.apache.spark.sql.sources._
import org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars

import scala.util.{Failure, Success, Try}



object SolrPredicateRules extends Logging {

  /**
  Constructor for testing, Takes solrIndexedFields as a function. This allows us
  to avoid reading from Solr.xml in testing functions.

  We assume that the basic Cassandra Rules have been applied first. Do nothing if there is a full
    partition_key restriction or primary key restriction. Otherwise try the solr conversion
  */
  private[cassandra] def apply(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    sparkConf: SparkConf,
    getSolrIndexedColumns: (TableDef, SparkConf) => Set[String],
    searchOptimizationEnabled: DseSearchOptimizationSetting): AnalyzedPredicates = {

    if (!searchOptimizationEnabled.enabled) {
      return predicates
    }

    //This could be done in the SCC as it's not Solr Specific
    val uselessIsNotNulls =
      findUselessIsNotNulls(predicates.handledByCassandra ++ predicates.handledBySpark, tableDef)
    val usefulPredicates =
      AnalyzedPredicates(predicates.handledByCassandra, predicates.handledBySpark -- uselessIsNotNulls)

    val pkRestriction = getPartitionKeyRestriction(usefulPredicates, tableDef).asInstanceOf[Set[Filter]]

    val primaryKeyRestrictionExists = (
      pkRestriction.nonEmpty &&
      pkRestriction.subsetOf(usefulPredicates.handledByCassandra) &&
      usefulPredicates.handledBySpark.isEmpty)

    if (primaryKeyRestrictionExists || alreadyContainsSolrQuery(usefulPredicates) ){
      logDebug("Not using Solr Optimizations")
      usefulPredicates
    } else {
      convertToSolrQuery(usefulPredicates, tableDef, getSolrIndexedColumns(tableDef, sparkConf), searchOptimizationEnabled, sparkConf)
    }
  }

  /**
    * Entry point for Spark Cassandra Connector. Reads SolrIndexedColumn information from
    * C*. See above Apply method for actual implementation.
    */
  def apply(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    sparkConf: SparkConf,
    searchOptimizationEnabled: DseSearchOptimizationSetting): AnalyzedPredicates = {

    apply(predicates, tableDef, sparkConf, getSolrIndexedColumnsFromSolrXML, searchOptimizationEnabled)
  }

  /**
    * Unfortunately the easiest current way to remotely determine what
    * columns have been indexed by solr is to read the Schema.xml. To obtain
    * this we check the Cassandra solr_admin table and pull the text from
    * the schema.xml.bak.
    *
    * Schema.xml.bak is the current "live" schema while schema.xml is the schema that
    * will be applied on refresh.
    */
  def getSolrIndexedColumnsFromSolrXML(tableDef: TableDef, sparkConf: SparkConf): Set[String] = {
    def solrIndexedFail(str: String): Set[String] = {
      logDebug(s"""Retrieval of Solr Index Info Failed: $str""")
      Set.empty
    }

    val SelectSolrSchema =
      s"""SELECT blobAsText(resource_value) FROM
         |solr_admin.solr_resources
         |where core_name = '${tableDef.keyspaceName}.${tableDef.tableName}'
         |and resource_name = 'schema.xml.bak' """.stripMargin

    Try {
      CassandraConnector(sparkConf)
        .withSessionDo(_.execute(SelectSolrSchema))
        .one()
        .getString(0)
    } match {
      case Success(schema: String) => Try {
        val schemaXML = scala.xml.XML.loadString(schema)

        schemaXML
          .child
          .filter(_.label == "fields")
          .head
          .child
          .filter(x => x.label == "field")
            //multiValued indexes are for graph internal use only
          .filter(!_.attributes.asAttrMap.getOrElse("multiValued", "false").toBoolean)
          .filter(_.attributes.asAttrMap.getOrElse("indexed", "false").toBoolean)
          .map(_.attribute("name").get.toString)
          .toSet[String]
      } match {
        case Success(indexed) => indexed
        case Failure(e) => solrIndexedFail(s"Unable to parse schema.xml.bak: $e")
      }
      case Failure(e) => solrIndexedFail(s"Unable to access Solr Info: $e")
    }
  }


  def alreadyContainsSolrQuery(predicates: AnalyzedPredicates): Boolean = {
    (predicates.handledByCassandra ++ predicates.handledBySpark).collect {
      case EqualTo(column, value) if column == SolrQuery => EqualTo(column, value)
    }.nonEmpty
  }

  /**
    * Checks that the filter and all dependent filters are indexed by
    * SolrIndexes.
    */
  def isConvertibleToSolr(filter: Filter, indexedCols: Set[String]): Boolean = filter match {
    case EqualTo(attr: String, value: Any) => indexedCols.contains(attr)
    case EqualNullSafe(attr: String, value: Any) => indexedCols.contains(attr)
    case In(attr: String, values: Array[Any]) => indexedCols.contains(attr)

    //Range Queries
    case GreaterThan(attr: String, value: Any) => indexedCols.contains(attr)
    case GreaterThanOrEqual(attr: String, value: Any) => indexedCols.contains(attr)
    case LessThan(attr: String, value: Any) => indexedCols.contains(attr)
    case LessThanOrEqual(attr: String, value: Any) => indexedCols.contains(attr)

    //Null Checks
    case IsNull(attr: String) => indexedCols.contains(attr)
    case IsNotNull(attr: String) => indexedCols.contains(attr)

    //Conjunctions
    case And(left: Filter, right: Filter) =>
      isConvertibleToSolr(left, indexedCols) && isConvertibleToSolr(right, indexedCols)
    case Or(left: Filter, right: Filter) =>
      isConvertibleToSolr(left, indexedCols) && isConvertibleToSolr(right, indexedCols)
    case Not(child: Filter) => isConvertibleToSolr(child, indexedCols)

    //StringMatching
    case StringStartsWith(attr: String, value: String) => indexedCols.contains(attr)
    case StringEndsWith(attr: String, value: String) => indexedCols.contains(attr)
    case StringContains(attr: String, value: String) => indexedCols.contains(attr)

    //Unknown
    case unknownFilter =>
      logError(s"Unknown Filter Type $unknownFilter")
      false
  }

  case class SolrFilter(solrQuery: String, references: Array[String]) extends Filter

  /** Sometimes the Java String Representation of the Value is not what solr is expecting
    * so we need to do conversions. Additionally we need to encode that encoded string
    * for JSON so we can pass it through to Solr.
    */
  def toSolrString(value: Any): String  = StringEscapeUtils.escapeJson(
    escapeQueryChars(
      value match {
        case date: java.sql.Timestamp => DateTimeFormatter.ISO_INSTANT.format(date.toInstant)
        case default => default.toString
      }
    )
  )

  def convertToSolrFilter(filter: Filter): SolrFilter = filter match {
    //Equivalence queries
    case EqualTo(attr: String, value: Any) => SolrFilter(s"${toSolrString(attr)}:${toSolrString(value)}", filter.references)
    case EqualNullSafe(attr: String, value: Any) => SolrFilter(s"${toSolrString(attr)}:${toSolrString(value)}", filter.references)
    case In(attr: String, values: Array[Any]) =>
      SolrFilter(s"${toSolrString(attr)}:(${values.map(toSolrString).mkString(" ")})", filter.references)

    //Range Queries
    case GreaterThan(attr: String, value: Any) => SolrFilter(s"${toSolrString(attr)}:{${toSolrString(value)} TO *]", filter.references)
    case GreaterThanOrEqual(attr: String, value: Any) =>
      SolrFilter(s"${toSolrString(attr)}:[${toSolrString(value)} TO *]", filter.references)

    case LessThan(attr: String, value: Any) => SolrFilter(s"${toSolrString(attr)}:[* TO ${toSolrString(value)}}", filter.references)
    case LessThanOrEqual(attr: String, value: Any) =>
      SolrFilter(s"${toSolrString(attr)}:[* TO ${toSolrString(value)}]", filter.references)

    //Null Checks
    case IsNull(attr: String) => SolrFilter(s"-${toSolrString(attr)}:[* TO *]", filter.references)
    case IsNotNull(attr: String) => SolrFilter(s"${toSolrString(attr)}:*", filter.references)

    //Conjunctions
    case And(left: Filter, right: Filter) =>
      SolrFilter(s"""(${convertToSolrFilter(left).solrQuery} AND ${convertToSolrFilter(right).solrQuery})""", filter.references)
    case Or(left: Filter, right: Filter) =>
      SolrFilter(s"""(${convertToSolrFilter(left).solrQuery} OR ${convertToSolrFilter(right).solrQuery})""", filter.references)
    case Not(child: Filter) =>
      SolrFilter(s"""-(${convertToSolrFilter(child).solrQuery})""", filter.references)

    //StringMatching
    case StringStartsWith(attr: String, value: String) => SolrFilter(s"${toSolrString(attr)}:${toSolrString(value)}*", filter.references)
    case StringEndsWith(attr: String, value: String) => SolrFilter(s"${toSolrString(attr)}:*${toSolrString(value)}", filter.references)
    case StringContains(attr: String, value: String) => SolrFilter(s"${toSolrString(attr)}:*${toSolrString(value)}*", filter.references)

    //Unknown
    case unknown =>
      throw new IllegalArgumentException(s"$unknown cannot be converted")
  }

  /**
    * Returns all predicates that can be treated as a single partition restriction.
    *
    * Follows the same rules as in SCC Basic Cassandra Predicates
    *
    * If no single partition restriction can be found returns nothing.
    */
  def getPartitionKeyRestriction(
    predicates: AnalyzedPredicates,
    tableDef: TableDef): Set[EqualTo] = {

    val equalsRestrictions =
      predicates
        .handledByCassandra
        .collect{ case equals: EqualTo => equals}

    val equalsRestrictionsByName =
      equalsRestrictions
        .map(eqClause => eqClause.attribute ->  eqClause)
        .toMap

    val partitionKeyColumnNames = tableDef.partitionKey.map(_.columnName).toSet

    val partitionKeyRestrictions =
      equalsRestrictions.filter{ case EqualTo(attr, _) => partitionKeyColumnNames.contains(attr)}

    val partitionKeyFullyRestricted =
      (partitionKeyColumnNames -- partitionKeyRestrictions.map(_.attribute)).isEmpty

    if (partitionKeyFullyRestricted)
      partitionKeyRestrictions
    else
      Set.empty[EqualTo]
  }

  /**
    * Whenever we have an attribute filter we don't need to do an IS_NOT_NULL check. This
    * also helps when we remove partition key restrictions because we don't keep useless
    * IsNotNulls which generate bad Solr.
    *
    */
  def findUselessIsNotNulls(filters: Set[Filter], tableDef: TableDef): Set[IsNotNull] = {
    val primaryKeyColumnNames = tableDef.primaryKey.map(_.columnName)

    val isNotNullColumns = filters.collect{ case IsNotNull(attr) => attr -> IsNotNull(attr) }.toMap

    val restrictedColumns = filters.collect {
      case LessThan(attr, _) => attr
      case LessThanOrEqual(attr, _) => attr
      case GreaterThan(attr, _) => attr
      case GreaterThanOrEqual(attr, _) => attr
      case EqualNullSafe(attr, _) => attr
      case EqualTo(attr, _) => attr
    }

    val uselessIsNotNulls = (primaryKeyColumnNames ++ restrictedColumns)
      .flatMap( attr => isNotNullColumns.get(attr))
      .toSet
    logDebug(s"Found isNotNulls $uselessIsNotNulls that are already not needed")

    uselessIsNotNulls
  }

  /**
    *
    * For all top level filters. If the filter can be changed into a SolrQuery
    * we will convert it and mark it as handled by Cassandra. All other filters will be
    * filtered within Spark
    *
    */
  def convertToSolrQuery(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    solrIndexedFields: Set[String],
    searchOptimizationEnabled: DseSearchOptimizationSetting,
    sparkConf: SparkConf): AnalyzedPredicates = {

    val allPredicates = predicates.handledByCassandra ++ predicates.handledBySpark

    val pkRestriction = getPartitionKeyRestriction(predicates, tableDef)
    if (pkRestriction.nonEmpty)
      logDebug(s"Partition restriction being withheld from Solr Conversion:  $pkRestriction")

    val possibleSolrPredicates = allPredicates -- pkRestriction

    val (solrConvertibleFilters, sparkFilters) = possibleSolrPredicates
      .partition(isConvertibleToSolr(_, solrIndexedFields))

    logDebug(s"Converting $solrConvertibleFilters to Solr Predicates")
    val solrFilters = solrConvertibleFilters.map(convertToSolrFilter)

    // Recommendation from Caleb :
    // Using separate filters ["filter","filter"] allows for reuse of filters
    val combinedFilterQuery = solrFilters
      .map { case SolrFilter(query, references) => s""""$query"""" }
      .mkString(", ")

    val solrString =  s"""{"q":"*:*", "fq":[$combinedFilterQuery]}"""

    /*
    See https://docs.datastax.com/en/datastax_enterprise/4.8/datastax_enterprise/srch/srchJSON.html#srchJSON__distQueryShardTol
    By setting these parameters for our estimate queries we will be more tolerant to partial results and shards not responding
    Which is ok because we are just trying to get an estimate
    */
    val FaultTolerant = Seq("\"shards.failover\": false", "\"shards.tolerant\": true").mkString(",")
    val solrStringNoFailoverTolerant = s"""{"q":"*:*", "fq":[$combinedFilterQuery], $FaultTolerant}"""

    val combinedSolrFilter: Filter = EqualTo(SolrQuery, solrString)

    val optimizedPredicates = AnalyzedPredicates(Set(combinedSolrFilter) ++ pkRestriction, sparkFilters)

    if (solrConvertibleFilters.isEmpty) {
      logDebug("No Solr Convertible Filters Found")
      predicates
    } else {
      searchOptimizationEnabled match {
        case Auto(ratio) =>
          val conn = CassandraConnector(sparkConf)
          val request = s"""SELECT COUNT(*) from "${tableDef.keyspaceName}"."${tableDef.tableName}" where solr_query=?"""

          logDebug(s"Checking total number of records")
          val (totalRecords:Long, queryRecords:Long) = conn.withSessionDo{ session =>
            //Disable Paging for the count requests since we are fault tolerant and paging cannot
            // be used during a fault tolerant request
            // https://docs.datastax.com/en/drivers/java/2.2/com/datastax/driver/core/Statement.html#setFetchSize-int-
            val totalRequest = new SimpleStatement(request, s"""{"q":"*:*", $FaultTolerant}""")
              .setFetchSize(Integer.MAX_VALUE)
            val queryRequest = new SimpleStatement(request, solrStringNoFailoverTolerant)
              .setFetchSize(Integer.MAX_VALUE)
            val totalFuture = session.executeAsync(totalRequest)
            val queryFuture = session.executeAsync(queryRequest)
            (totalFuture.get(5, TimeUnit.SECONDS).one().getLong(0),
              queryFuture.get(5, TimeUnit.SECONDS).one().getLong(0))
          }

          val queryRatio = if (totalRecords == 0) 0 else queryRecords.toDouble / totalRecords.toDouble
          if (queryRatio > ratio) {
            logDebug(s"Requesting $queryRatio of the total records. Required to be less than $ratio for DSE Search, falling back to Full Table Scan")
            predicates
          }
          else {
            logDebug(s"Requesting $queryRatio of the total records. Less than $ratio, using DSE Search Optimized request")
            optimizedPredicates
          }
        case On =>
          logDebug(s"Converted $solrConvertibleFilters to $combinedSolrFilter")
          optimizedPredicates
        case Off =>
          predicates
      }

    }
  }
}


