package fraud.redis

import cats.effect.IO
import cats.syntax.all._
import dev.profunktor.redis4cats.RedisCommands

import fraud.domain.{Alert, BlockReason, DedupKey, Evidence, Transaction}

object Blocklist {

  private val cardsKey = "blocklist:cards"
  private val merchantsKey = "blocklist:merchants"

  def seed(redis: RedisCommands[IO, String, String], config: Config): IO[Unit] =
    List(cardsKey -> config.blockedCards, merchantsKey -> config.blockedMerchants).traverse_ {
      case (_, values) if values.isEmpty => IO.unit
      case (key, values)                 => redis.sAdd(key, values.toSeq: _*).void
    }

  def check(redis: RedisCommands[IO, String, String], tx: Transaction): IO[Option[Alert]] =
    redis
      .pipeline[Boolean] { store =>
        List(
          redis.sIsMember(cardsKey, tx.cardId.value).flatMap(store.set("card")),
          redis.sIsMember(merchantsKey, tx.merchantId.value).flatMap(store.set("merchant"))
        )
      }
      .map { results =>
        val reason =
          if (results.get("card").contains(true)) Some(BlockReason.BlockedCard(tx.cardId))
          else if (results.get("merchant").contains(true)) Some(BlockReason.BlockedMerchant(tx.merchantId))
          else None
        reason.map(alert(tx, _))
      }

  private def alert(tx: Transaction, reason: BlockReason): Alert =
    Alert(
      DedupKey(s"${tx.cardId.value}:F1:${tx.eventTime.toEpochMilli / 300000}"),
      tx.cardId,
      tx.transactionId,
      tx.eventTime,
      Evidence.Blocklisted(reason)
    )
}
