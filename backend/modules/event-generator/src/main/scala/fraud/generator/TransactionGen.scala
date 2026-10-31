package fraud.generator

import java.time.Instant

import fs2.{Pure, Stream}

import fraud.domain._
import fraud.domain.MerchantCategory._

final case class RawCity(lat: Double, lon: Double, country: String)

final case class RawCard(id: String, home: RawCity)

final case class RawTransaction(
    id: Long,
    merchant: Long,
    category: MerchantCategory,
    amount: Long,
    city: RawCity
)

object TransactionGen {

  val categories: Vector[MerchantCategory] =
    Vector(Grocery, Fuel, Restaurant, Electronics, Travel, Entertainment, Pharmacy, CashWithdrawal)

  val cities: Vector[RawCity] = Vector(
    RawCity(44.43, 26.10, "RO"),
    RawCity(52.52, 13.40, "DE"),
    RawCity(48.86, 2.35, "FR"),
    RawCity(40.42, -3.70, "ES"),
    RawCity(41.90, 12.50, "IT")
  )

  def cards(count: Int): Rand[Vector[RawCard]] =
    Rand.listOfN(count, Rand.oneOf(cities)).map { homes =>
      homes.zipWithIndex.map { case (home, i) => RawCard(s"card-$i", home) }
    }

  def rawFor(card: RawCard): Rand[RawTransaction] =
    for {
      id       <- Rand.nextLong
      merchant <- Rand.between(0, 200)
      category <- Rand.oneOf(categories)
      amount   <- Rand.between(100, 20000)
    } yield RawTransaction(id, merchant, category, amount, card.home)

  def next(cards: Vector[RawCard]): Rand[(RawCard, RawTransaction)] =
    for {
      card <- Rand.oneOf(cards)
      tx   <- rawFor(card)
    } yield (card, tx)

  def stream(cards: Vector[RawCard], seed: Seed): Stream[Pure, (RawCard, RawTransaction)] =
    Stream.unfold(seed)(s => Some(next(cards).run(s)))

  def toTransaction(rawCard: RawCard, at: Instant, r: RawTransaction): Either[DomainError, Transaction] =
    for {
      card     <- CardId.parse(rawCard.id)
      txId     <- TransactionId.parse(s"txn-${r.id}")
      merchant <- MerchantId.parse(s"m-${r.merchant}")
      amount   <- AmountMinor.parse(r.amount)
      country  <- CountryCode.parse(r.city.country)
      point    <- GeoPoint.parse(r.city.lat, r.city.lon, country)
    } yield Transaction(txId, card, merchant, r.category, amount, Origin.Physical(point), at)
}
