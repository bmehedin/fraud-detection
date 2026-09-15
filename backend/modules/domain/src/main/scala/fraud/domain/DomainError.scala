package fraud.domain

sealed trait DomainError

object DomainError {
  case object EmptyTransactionId extends DomainError
  case object EmptyCardId extends DomainError
  case object EmptyMerchantId extends DomainError
  final case class InvalidCountryCode(value: String) extends DomainError
  final case class NonPositiveAmount(minor: Long) extends DomainError
  final case class LatOutOfRange(lat: Double) extends DomainError
  final case class LonOutOfRange(lon: Double) extends DomainError
  final case class InvalidIpAddress(value: String) extends DomainError
}
