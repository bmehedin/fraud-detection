package fraud.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax._

final case class AmountMinor private (value: Long) extends AnyVal

object AmountMinor {
  def parse(minor: Long): Either[DomainError, AmountMinor] =
    if (minor <= 0L) Left(DomainError.NonPositiveAmount(minor))
    else Right(new AmountMinor(minor))

  implicit val decoder: Decoder[AmountMinor] =
    Decoder.decodeLong.emap(parse(_).left.map(_.toString))
}

final case class GeoPoint private (lat: Double, lon: Double, country: CountryCode)

object GeoPoint {

  def parse(lat: Double, lon: Double, country: CountryCode): Either[DomainError, GeoPoint] =
    for {
      checkedLat <- validLat(lat)
      checkedLon <- validLon(lon)
    } yield new GeoPoint(checkedLat, checkedLon, country)

  private def validLat(lat: Double): Either[DomainError, Double] =
    if (lat.isNaN || lat < -90.0 || lat > 90.0) Left(DomainError.LatOutOfRange(lat))
    else Right(lat)

  private def validLon(lon: Double): Either[DomainError, Double] =
    if (lon.isNaN || lon < -180.0 || lon > 180.0) Left(DomainError.LonOutOfRange(lon))
    else Right(lon)

  implicit val encoder: Encoder[GeoPoint] =
    Encoder.instance { p =>
      Json.obj("lat" := p.lat, "lon" := p.lon, "country" := p.country.value)
    }

  implicit val decoder: Decoder[GeoPoint] =
    Decoder.instance { c =>
      for {
        lat     <- c.get[Double]("lat")
        lon     <- c.get[Double]("lon")
        country <- c.get[CountryCode]("country")
        point   <- parse(lat, lon, country).left.map(e => DecodingFailure(e.toString, c.history))
      } yield point
    }
}

sealed trait  Origin

object Origin {
  final case class Physical(point: GeoPoint) extends Origin
  final case class Remote(ip: IpAddress) extends Origin

  implicit val encoder: Encoder[Origin] =
    Encoder.instance {
      case Physical(point) => Json.obj("type" := "Physical", "point" := point)
      case Remote(ip)      => Json.obj("type" := "Remote", "ip" := ip.value)
    }

  implicit val decoder: Decoder[Origin] =
    Decoder.instance { c =>
      c.get[String]("type").flatMap {
        case "Physical" => c.get[GeoPoint]("point").map(Physical(_))
        case "Remote"   => c.get[IpAddress]("ip").map(Remote(_))
        case other      => Left(DecodingFailure(s"unknown origin type '$other'", c.history))
      }
    }
}

sealed trait MerchantCategory

object MerchantCategory {
  case object Grocery extends MerchantCategory
  case object Fuel extends MerchantCategory
  case object Restaurant extends MerchantCategory
  case object Electronics extends MerchantCategory
  case object Travel extends MerchantCategory
  case object Entertainment extends MerchantCategory
  case object Pharmacy extends MerchantCategory
  case object CashWithdrawal extends MerchantCategory

  implicit val encoder: Encoder[MerchantCategory] =
    Encoder.encodeString.contramap(_.toString)

  private val all: List[MerchantCategory] =
    List(Grocery, Fuel, Restaurant, Electronics, Travel, Entertainment, Pharmacy, CashWithdrawal)

  implicit val decoder: Decoder[MerchantCategory] =
    Decoder.decodeString.emap(raw => all.find(_.toString == raw).toRight(s"unknown merchant category '$raw'"))
}

final case class Transaction(
    transactionId: TransactionId,
    cardId: CardId,
    merchantId: MerchantId,
    merchantCategory: MerchantCategory,
    amountMinor: AmountMinor,
    origin: Origin,
    eventTime: Instant
)

object Transaction {

  implicit val encoder: Encoder[Transaction] =
    Encoder.instance { t =>
      Json.obj(
        "transactionId" := t.transactionId.value,
        "cardId" := t.cardId.value,
        "merchantId" := t.merchantId.value,
        "merchantCategory" := t.merchantCategory,
        "amountMinor" := t.amountMinor.value,
        "origin" := t.origin,
        "eventTime" := t.eventTime.truncatedTo(ChronoUnit.MILLIS)
      )
    }

  implicit val decoder: Decoder[Transaction] =
    Decoder.forProduct7(
      "transactionId",
      "cardId",
      "merchantId",
      "merchantCategory",
      "amountMinor",
      "origin",
      "eventTime"
    )(Transaction.apply)
}
