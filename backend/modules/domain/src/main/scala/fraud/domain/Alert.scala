package fraud.domain

import java.time.Instant

final case class DedupKey(value: String) extends AnyVal

sealed trait RuleId

object RuleId {
  case object F1 extends RuleId
  case object F2 extends RuleId
  case object F3 extends RuleId
  case object S1 extends RuleId
  case object S2 extends RuleId
  case object S3 extends RuleId
}

sealed trait Severity

object Severity {
  case object Low extends Severity
  case object Medium extends Severity
  case object High extends Severity
}

sealed trait BlockReason

object BlockReason {
  final case class BlockedCard(cardId: CardId) extends BlockReason
  final case class BlockedMerchant(merchantId: MerchantId) extends BlockReason
}

sealed trait Evidence {
  def rule: RuleId
  def severity: Severity
}

object Evidence {

  final case class Blocklisted(reason: BlockReason) extends Evidence {
    val rule: RuleId = RuleId.F1
    val severity: Severity = Severity.High
  }

  final case class AmountCap(amountMinor: Long, capMinor: Long) extends Evidence {
    val rule: RuleId = RuleId.F2
    val severity: Severity = Severity.Medium
  }

  final case class Velocity(count: Int, windowSeconds: Int) extends Evidence {
    val rule: RuleId = RuleId.F3
    val severity: Severity = Severity.High
  }

  final case class AmountDeviation(amountMinor: Long, thresholdMinor: Double) extends Evidence {
    val rule: RuleId = RuleId.S1
    val severity: Severity = Severity.Medium
  }

  final case class ImpossibleTravel(from: GeoPoint, to: GeoPoint, kmh: Double) extends Evidence {
    val rule: RuleId = RuleId.S2
    val severity: Severity = Severity.High
  }

  final case class UnusualCategory(
      category: MerchantCategory,
      amountMinor: Long,
      p95Minor: Long
  ) extends Evidence {
    val rule: RuleId = RuleId.S3
    val severity: Severity = Severity.Medium
  }
}

final case class Alert(
    dedupKey: DedupKey,
    cardId: CardId,
    triggeredBy: TransactionId,
    detectedAt: Instant,
    evidence: Evidence
) {
  def rule: RuleId = evidence.rule
  def severity: Severity = evidence.severity
}
