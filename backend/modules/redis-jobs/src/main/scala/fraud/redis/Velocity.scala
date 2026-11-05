package fraud.redis

import cats.effect.IO
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange}

import fraud.domain.{Alert, DedupKey, Evidence, Transaction}

object Velocity {

  def check(redis: RedisCommands[IO, String, String], config: Config, tx: Transaction): IO[Option[Alert]] = {
    val key = s"velocity:${tx.cardId.value}"
    val now = tx.eventTime.toEpochMilli
    val windowStart = now - config.velocityWindow.toMillis

    redis
      .pipeline[Long] { store =>
        List(
          redis.zAdd(key, None, ScoreWithValue(Score(now.toDouble), tx.transactionId.value)).void,
          redis.zRemRangeByScore(key, ZRange(0L, windowStart)).void,
          redis.zCard(key).flatMap(store.set("count")),
          redis.expire(key, config.velocityWindow).void
        )
      }
      .map(_.get("count").filter(_ > config.velocityMax).map(count => alert(config, tx, count)))
  }

  private def alert(config: Config, tx: Transaction, count: Long): Alert =
    Alert(
      DedupKey(s"${tx.cardId.value}:F3:${tx.eventTime.toEpochMilli / 300000}"),
      tx.cardId,
      tx.transactionId,
      tx.eventTime,
      Evidence.Velocity(count.toInt, config.velocityWindow.toSeconds.toInt)
    )
}
