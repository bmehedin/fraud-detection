package fraud.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ValuesSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  private val romania = CountryCode.parse("RO").getOrElse(fail("RO must be a valid country"))

  "AmountMinor.parse" should "accept one cent" in {
    AmountMinor.parse(1L).map(_.value) shouldBe Right(1L)
  }

  it should "reject zero" in {
    AmountMinor.parse(0L) shouldBe Left(DomainError.NonPositiveAmount(0L))
  }

  it should "reject any negative amount" in {
    forAll { (minor: Long) =>
      whenever(minor <= 0L) {
        AmountMinor.parse(minor) shouldBe Left(DomainError.NonPositiveAmount(minor))
      }
    }
  }

  "GeoPoint.parse" should "accept coordinates inside the ranges" in {
    GeoPoint.parse(44.4268, 26.1025, romania).map(_.lat) shouldBe Right(44.4268)
  }

  it should "accept the boundary values" in {
    GeoPoint.parse(90.0, 180.0, romania).isRight shouldBe true
    GeoPoint.parse(-90.0, -180.0, romania).isRight shouldBe true
  }

  it should "reject a latitude above 90" in {
    GeoPoint.parse(90.1, 0.0, romania) shouldBe Left(DomainError.LatOutOfRange(90.1))
  }

  it should "reject a latitude that is not a number" in {
    GeoPoint.parse(Double.NaN, 0.0, romania).isLeft shouldBe true
  }

  it should "reject a longitude below -180" in {
    GeoPoint.parse(0.0, -180.1, romania) shouldBe Left(DomainError.LonOutOfRange(-180.1))
  }

  it should "report the latitude first when both are out of range" in {
    GeoPoint.parse(91.0, 181.0, romania) shouldBe Left(DomainError.LatOutOfRange(91.0))
  }

  "IpAddress.parse" should "accept an IPv4 address" in {
    IpAddress.parse("203.0.113.47").map(_.value) shouldBe Right("203.0.113.47")
  }

  it should "reject an IPv6 address" in {
    IpAddress.parse("2001:db8::1") shouldBe Left(DomainError.InvalidIpAddress("2001:db8::1"))
  }

  it should "reject an octet above 255" in {
    IpAddress.parse("256.1.1.1") shouldBe Left(DomainError.InvalidIpAddress("256.1.1.1"))
  }

  it should "reject three octets" in {
    IpAddress.parse("1.2.3") shouldBe Left(DomainError.InvalidIpAddress("1.2.3"))
  }

  it should "reject a bare number" in {
    IpAddress.parse("12345") shouldBe Left(DomainError.InvalidIpAddress("12345"))
  }

  it should "reject an empty value" in {
    IpAddress.parse("") shouldBe Left(DomainError.InvalidIpAddress(""))
  }

  it should "reject an octet with a leading zero" in {
    IpAddress.parse("192.168.01.1") shouldBe Left(DomainError.InvalidIpAddress("192.168.01.1"))
  }
}
