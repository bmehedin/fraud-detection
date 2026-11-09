package fraud.redis

import scala.io.Source

import cats.effect.IO
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effects.ScriptOutputType

import fraud.domain.{Alert, DedupKey, Evidence, Transaction}

object Velocity {

  def load(redis: RedisCommands[IO, String, String]): IO[String] =
    IO.blocking(Source.fromResource("velocity.lua").mkString).flatMap(script => redis.scriptLoad(script))

  def check(redis: RedisCommands[IO, String, String], config: Config, sha: String, tx: Transaction): IO[Option[Alert]] = {
    val key = s"velocity:${tx.cardId.value}"
    val now = tx.eventTime.toEpochMilli
    val windowStart = now - config.velocityWindow.toMillis
    val args = List(now.toString, windowStart.toString, tx.transactionId.value, config.velocityWindow.toSeconds.toString)

    redis
      .evalSha(sha, ScriptOutputType.Integer[String], List(key), args)
      .map(count => Option.when(count > config.velocityMax)(alert(config, tx, count)))
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
