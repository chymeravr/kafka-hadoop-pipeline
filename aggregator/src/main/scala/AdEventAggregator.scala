/**
  * Created by rubbal on 4/2/17.
  */

import java.beans.Transient
import java.net.URI
import java.time.{Duration, Instant, ZoneId, ZonedDateTime}
import java.util.Base64

import com.chymeravr.dfs.records.{HourlyDimension, HourlyTimestamp, Metrics}
import com.chymeravr.schemas.eventreceiver.EventType
import com.chymeravr.schemas.kafka.AttributedEvent
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.Logger
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.thrift.TDeserializer

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

object AdEventAggregator {
  @Transient val logger: Logger = Logger.getLogger(this.getClass.getName)
  val utcId: ZoneId = ZoneId.of("UTC")

  def main(args: Array[String]) {
    logger.info("Application starting")

    val currentDateTime = ZonedDateTime.now(utcId)
    val sc = new SparkContext(new SparkConf().setAppName(this.getClass.getName))
    val conf = sc.hadoopConfiguration
    val hdfsUrl = args(0)
    val inputPathPrefix = args(1)
    val hourOffset = args(2).toInt

    val currentMinusOffset = currentDateTime.minus(Duration.ofHours(hourOffset - 1))
    val windowStartTime = ZonedDateTime.of(currentMinusOffset.getYear,
      currentMinusOffset.getMonthValue,
      currentMinusOffset.getDayOfMonth,
      currentMinusOffset.getHour,
      0, 0, 0, utcId)

    val windowStartTimeEpoch = windowStartTime.toInstant.toEpochMilli

    val fs = FileSystem.get(new URI(hdfsUrl), conf)
    var fileNamesBuilder = ListBuffer[String]()
    for (x <- Range(0, hourOffset)) {
      val inputTime = currentDateTime.minus(Duration.ofHours(x))
      val year = inputTime.getYear
      val month = inputTime.getMonth.getValue
      val day = inputTime.getDayOfMonth
      val hour = inputTime.getHour
      fileNamesBuilder += hdfsUrl + inputPathPrefix + f"year=$year/month=$month%02d/day=$day%02d/hour=$hour%02d/"
    }

    logger.info(f"Potential input folders: $fileNamesBuilder")
    fileNamesBuilder = fileNamesBuilder.filter(path => fs.exists(new Path(path)))

    logger.info(f"Existing input folders: ")
    val fileNames = fileNamesBuilder.mkString(",").toString

    logger.info(f"Current time: $currentDateTime")
    logger.info(f"Window start time: $windowStartTime")
    logger.info(f"Window start time epoch: $windowStartTimeEpoch")

    val metrics = sc.textFile(fileNames)
    val events = metrics.map(parseEvent)
    val parsedEvents = events.filter(_.isSuccess)
    var filteredEvents = parsedEvents.map(_.get)
    // Filter older events and no need to aggregate ad_view_metrics
    filteredEvents = filteredEvents.filter(event => event.getServingLog.timestamp > windowStartTimeEpoch &&
      event.eventLog.eventType != EventType.AD_VIEW_METRICS)
    val instrumentedEvents = filteredEvents.map(calculateMetrics)
    val aggregates = instrumentedEvents.reduceByKey((a, b) => {
      val metrics = new Metrics()
      metrics.setClicks(a.clicks + b.clicks)
      metrics.setAmount(a.amount + b.amount)
      metrics.setErrors(a.errors + b.errors)
    })

    aggregates.collect().foreach(x => logger.info(x._1 + "::" + x._2))

    logger.info("Shut down app")

  }

  def calculateMetrics(event: AttributedEvent): (AttributedEvent, Metrics) = {
    val ts = event.getServingLog.timestamp
    val serveTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("UTC"))
    val dimension = new HourlyDimension(new HourlyTimestamp(serveTime.getYear.toShort,
      serveTime.getMonth.getValue.toShort, serveTime.getDayOfMonth.toShort, serveTime.getHour.toShort),
      event.getServingLog.impressionInfo.adId)

    val metrics = event.eventLog.eventType match {
      case EventType.AD_CLICK =>
        val m = new Metrics()
        m.setClicks(1)
        m.setAmount(event.servingLog.impressionInfo.costPrice)
      case EventType.AD_SHOW =>
        val m = new Metrics()
        m.setImpressions(1)
        m.setAmount(event.servingLog.impressionInfo.costPrice)
      case EventType.AD_CLOSE => val m = new Metrics(); m.setClose(1)
      case EventType.ERROR => val m = new Metrics(); m.setErrors(1)
    }

    metrics.setAmount(event.getServingLog.getImpressionInfo.getCostPrice)
    (dimension, metrics, ts)
  }

  def parseEvent(line: String): Try[AttributedEvent] = {
    Try {
      val tokens = line.split(" ")
      val rqId = tokens(0).trim
      val serializedObject = tokens(1).trim
      val event = new AttributedEvent()
      new TDeserializer().deserialize(event, Base64.getDecoder.decode(serializedObject))
      event
    } match {
      case Success(lines) => Success(lines)
      case Failure(ex) => println(s"Problem rendering URL content: $ex"); Failure(ex)
    }
  }
}