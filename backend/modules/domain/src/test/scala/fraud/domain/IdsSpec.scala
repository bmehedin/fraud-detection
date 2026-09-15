package fraud.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class IdsSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  "CardId.parse" should "accept a non-blank value" in {
    CardId.parse("card-000412").map(_.value) shouldBe Right("card-000412")
  }

  it should "reject an empty value" in {
    CardId.parse("") shouldBe Left(DomainError.EmptyCardId)
  }

  it should "reject a value of only whitespace" in {
    CardId.parse("   ") shouldBe Left(DomainError.EmptyCardId)
  }

  it should "return exactly what it was given" in {
    forAll { (raw: String) =>
      whenever(raw.trim.nonEmpty) {
        CardId.parse(raw).map(_.value) shouldBe Right(raw)
      }
    }
  }

  "MerchantId.parse" should "accept a non-blank value" in {
    MerchantId.parse("merch-0091").map(_.value) shouldBe Right("merch-0091")
  }

  it should "reject an empty value" in {
    MerchantId.parse("") shouldBe Left(DomainError.EmptyMerchantId)
  }

  "TransactionId.parse" should "accept a non-blank value" in {
    TransactionId.parse("8f1c7c4a-9b2e-4d31-9d5b-1f2a3b4c5d6e").map(_.value) shouldBe
      Right("8f1c7c4a-9b2e-4d31-9d5b-1f2a3b4c5d6e")
  }

  it should "reject an empty value" in {
    TransactionId.parse("") shouldBe Left(DomainError.EmptyTransactionId)
  }

  "CountryCode.parse" should "accept two uppercase letters" in {
    CountryCode.parse("RO").map(_.value) shouldBe Right("RO")
  }

  it should "reject lowercase" in {
    CountryCode.parse("ro") shouldBe Left(DomainError.InvalidCountryCode("ro"))
  }

  it should "reject three letters" in {
    CountryCode.parse("ROU") shouldBe Left(DomainError.InvalidCountryCode("ROU"))
  }

  it should "reject digits" in {
    CountryCode.parse("12") shouldBe Left(DomainError.InvalidCountryCode("12"))
  }

  it should "reject an empty value" in {
    CountryCode.parse("") shouldBe Left(DomainError.InvalidCountryCode(""))
  }
}
