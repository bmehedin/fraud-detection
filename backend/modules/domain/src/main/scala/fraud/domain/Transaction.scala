package fraud.domain

import java.time.Instant

final case class AmountMinor private (value: Long) extends AnyVal

object AmountMinor {
  def parse(minor: Long): Either[DomainError, AmountMinor] =
    if (minor <= 0L) Left(DomainError.NonPositiveAmount(minor))
    else Right(new AmountMinor(minor))
}

final case class GeoPoint private (lat: Double, lon: Double, country: CountryCode)

object GeoPoint {
  def parse(lat: Double, lon: Double, country: CountryCode): Either[DomainError, GeoPoint] =
    if (lat.isNaN || lat < -90.0 || lat > 90.0) Left(DomainError.LatOutOfRange(lat))
    else if (lon.isNaN || lon < -180.0 || lon > 180.0) Left(DomainError.LonOutOfRange(lon))
    else Right(new GeoPoint(lat, lon, country))
}

sealed trait Origin

object Origin {
  final case class Physical(point: GeoPoint) extends Origin
  final case class Remote(ip: IpAddress) extends Origin
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
