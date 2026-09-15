package fraud.domain

import java.time.Instant

final case class CardProfile(
    cardId: CardId,
    txnCount: Long,
    meanAmountMinor: Double,
    stdDevAmountMinor: Double,
    p95AmountMinor: Long,
    knownCategories: Set[MerchantCategory],
    homeCountry: CountryCode,
    computedAt: Instant
)
