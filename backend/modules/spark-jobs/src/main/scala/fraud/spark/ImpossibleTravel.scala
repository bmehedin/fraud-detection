package fraud.spark

import java.time.Instant

import org.apache.spark.sql.Encoders
import org.apache.spark.sql.streaming._

final case class Point(lat: Double, lon: Double, country: String)

final case class Visit(cardId: String, transactionId: String, eventTime: Instant, point: Point)

final case class TravelHit(
    cardId: String,
    triggeredBy: String,
    detectedAt: Instant,
    from: Point,
    to: Point,
    kmh: Double
)

object ImpossibleTravel {

  private val EarthRadiusKm = 6371.0

  def distanceKm(a: Point, b: Point): Double = {
    val dLat = math.toRadians(b.lat - a.lat)
    val dLon = math.toRadians(b.lon - a.lon)
    val h = math.pow(math.sin(dLat / 2), 2) +
      math.cos(math.toRadians(a.lat)) * math.cos(math.toRadians(b.lat)) * math.pow(math.sin(dLon / 2), 2)
    2 * EarthRadiusKm * math.asin(math.sqrt(h))
  }

  def kmh(a: Visit, b: Visit): Double = {
    val gapMs = math.max(math.abs(b.eventTime.toEpochMilli - a.eventTime.toEpochMilli), 1000L)
    distanceKm(a.point, b.point) / (gapMs / 3600000.0)
  }
}

class ImpossibleTravelProcessor(maxKmh: Double) extends StatefulProcessor[String, Visit, TravelHit] {

  @transient private var visits: MapState[String, Visit] = _

  override def init(outputMode: OutputMode, timeMode: TimeMode): Unit =
    visits = getHandle.getMapState("visits", Encoders.STRING, Encoders.product[Visit], TTLConfig.NONE)

  override def handleInputRows(
      cardId: String,
      rows: Iterator[Visit],
      timerValues: TimerValues
  ): Iterator[TravelHit] = {
    val watermark = timerValues.getCurrentWatermarkInMs()
    val incoming = rows
      .filter(v => v.eventTime.toEpochMilli >= watermark && !visits.containsKey(v.transactionId))
      .toList
      .sortBy(_.eventTime)

    var known = visits.values().toList.sortBy(_.eventTime)
    val hits = incoming.flatMap { v =>
      val previous = known.filter(k => !k.eventTime.isAfter(v.eventTime)).lastOption
      val next = known.find(_.eventTime.isAfter(v.eventTime))
      visits.updateValue(v.transactionId, v)
      // prepend (::) current visit to the known ones and sort by time - infix notation
      known = (v :: known).sortBy(_.eventTime)
      // apply a transformation such that we obtain the previous and current visits tuple
      // we join that tuple with the (current, next visit) tuple if it exists
      // pattern matching allows us to compute the speed between the two tuples
      // if the speed is exceeded, a travel hit instance is collected. there may be up to two
      // travel hits when comparing visits of the two tuples. (between now and prev or between now and next)
      (previous.map(p => (p, v)) ++ next.map(n => (v, n)))
        .map { case (a, b) => (a, b, ImpossibleTravel.kmh(a, b)) }
        .collect { case (a, b, speed) if speed > maxKmh =>
          TravelHit(cardId, v.transactionId, v.eventTime, a.point, b.point, speed)
        }
    }

    known.filter(_.eventTime.toEpochMilli < watermark).dropRight(1).foreach(k => visits.removeKey(k.transactionId))
    hits.iterator
  }
}
