package fraud.domain

import io.circe.Decoder

final case class TransactionId private (value: String) extends AnyVal

object TransactionId {
  def parse(raw: String): Either[DomainError, TransactionId] =
    if (raw.trim.isEmpty) Left(DomainError.EmptyTransactionId)
    else Right(new TransactionId(raw))

  implicit val decoder: Decoder[TransactionId] =
    Decoder.decodeString.emap(parse(_).left.map(_.toString))
}

final case class CardId private (value: String) extends AnyVal

object CardId {
  def parse(raw: String): Either[DomainError, CardId] =
    if (raw.trim.isEmpty) Left(DomainError.EmptyCardId)
    else Right(new CardId(raw))

  implicit val decoder: Decoder[CardId] =
    Decoder.decodeString.emap(parse(_).left.map(_.toString))
}

final case class MerchantId private (value: String) extends AnyVal

object MerchantId {
  def parse(raw: String): Either[DomainError, MerchantId] =
    if (raw.trim.isEmpty) Left(DomainError.EmptyMerchantId)
    else Right(new MerchantId(raw))

  implicit val decoder: Decoder[MerchantId] =
    Decoder.decodeString.emap(parse(_).left.map(_.toString))
}

final case class CountryCode private (value: String) extends AnyVal

object CountryCode {
  def parse(raw: String): Either[DomainError, CountryCode] =
    if (raw.length == 2 && raw.forall(c => c >= 'A' && c <= 'Z')) Right(new CountryCode(raw))
    else Left(DomainError.InvalidCountryCode(raw))

  implicit val decoder: Decoder[CountryCode] =
    Decoder.decodeString.emap(parse(_).left.map(_.toString))
}

final case class IpAddress private (value: String) extends AnyVal

object IpAddress {

  def parse(raw: String): Either[DomainError, IpAddress] =
    if (isIpv4(raw)) Right(new IpAddress(raw))
    else Left(DomainError.InvalidIpAddress(raw))

  implicit val decoder: Decoder[IpAddress] =
    Decoder.decodeString.emap(parse(_).left.map(_.toString))

  private def isIpv4(raw: String): Boolean = {
    val octets = raw.split("[.]", -1)
    octets.length == 4 && octets.forall(isOctet)
  }

  private def isOctet(part: String): Boolean =
    part.nonEmpty &&
      part.length <= 3 &&
      part.forall(_.isDigit) &&
      part.toInt <= 255 &&
      (part.length == 1 || part.charAt(0) != '0')
}
