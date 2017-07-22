package com.agoda.kafka.connector.jdbc.services

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.util.{Date, GregorianCalendar, TimeZone}

import com.agoda.kafka.connector.jdbc.JdbcSourceConnectorConstants
import com.agoda.kafka.connector.jdbc.models.DatabaseProduct
import com.agoda.kafka.connector.jdbc.models.DatabaseProduct.{MsSQL, MySQL, PostgreSQL}
import com.agoda.kafka.connector.jdbc.models.Mode.TimestampMode
import com.agoda.kafka.connector.jdbc.utils.DataConverter
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.source.SourceRecord

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * @constructor
  * @param databaseProduct type of database server
  * @param storedProcedureName name of the stored procedure
  * @param batchSize number of records returned in each batch
  * @param batchSizeVariableName name of the batch size variable in stored procedure
  * @param timestampVariableName name of the timestamp offset variable in stored procedure
  * @param timestampOffset value of current timestamp offset
  * @param timestampFieldName timestamp offset field name in returned records
  * @param topic name of kafka topic where records are stored
  * @param keyFieldOpt optional key field name in returned records
  */
case class TimeBasedDataService(databaseProduct: DatabaseProduct,
                                storedProcedureName: String,
                                batchSize: Int,
                                batchSizeVariableName: String,
                                timestampVariableName: String,
                                var timestampOffset: Long,
                                timestampFieldName: String,
                                topic: String,
                                keyFieldOpt: Option[String]) extends DataService {

  private val UTC_CALENDAR = new GregorianCalendar(TimeZone.getTimeZone("UTC"))

  override protected def createPreparedStatement(connection: Connection): Try[PreparedStatement] = Try {
    val preparedStatement = databaseProduct match {
      case MsSQL      => connection.prepareStatement(s"EXECUTE $storedProcedureName @$timestampVariableName = ?, @$batchSizeVariableName = ?")
      case MySQL      => connection.prepareStatement(s"CALL $storedProcedureName (@$timestampVariableName := ?, @$batchSizeVariableName := ?)")
      case PostgreSQL => connection.prepareStatement(s"SELECT $storedProcedureName (?, ?)")
    }
    preparedStatement.setTimestamp(1, new Timestamp(timestampOffset), UTC_CALENDAR)
    preparedStatement.setObject(2, batchSize)
    preparedStatement
  }

  override protected def extractRecords(resultSet: ResultSet, schema: Schema): Try[Seq[SourceRecord]] = Try {
    val sourceRecords = ListBuffer.empty[SourceRecord]
    var max = timestampOffset
    while (resultSet.next()) {
      DataConverter.convertRecord(schema, resultSet) map { record =>
        val time = record.get(timestampFieldName).asInstanceOf[Date].getTime
        max = if(time > max) time else max
        keyFieldOpt match {
          case Some(keyField) =>
            sourceRecords += new SourceRecord(
              Map(JdbcSourceConnectorConstants.STORED_PROCEDURE_NAME_KEY -> storedProcedureName).asJava,
              Map(TimestampMode.entryName -> time).asJava, topic, null, schema, record.get(keyField), schema, record
            )
          case None           =>
            sourceRecords += new SourceRecord(
              Map(JdbcSourceConnectorConstants.STORED_PROCEDURE_NAME_KEY -> storedProcedureName).asJava,
              Map(TimestampMode.entryName -> time).asJava, topic, schema, record
            )
        }
      }
    }
    timestampOffset = max
    println("%%%%%%%%%%%%%%%%%%%%%%%% Time Based %%%")
    println(sourceRecords.toList)
    println("%%%%%%%%%%%%%%%%%%%%%%%%")
    sourceRecords
  }

  override def toString: String = {
    s"""
       |{
       |   "name" : ${this.getClass.getSimpleName}
       |   "mode" : ${TimestampMode.entryName}
       |   "stored-procedure.name" : $storedProcedureName
       |}
    """.stripMargin
  }
}