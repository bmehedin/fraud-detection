package fraud.redis

import scala.concurrent.duration._

final case class Config(
    bootstrapServers: String,
    topic: String,
    groupId: String,
    dlqTopic: String,
    alertsTopic: String,
    redisUri: String,
    velocityMax: Int,
    velocityWindow: FiniteDuration,
    blockedCards: Set[String],
    blockedMerchants: Set[String],
    alertDedupTtl: FiniteDuration
)

object Config {

  def parse(env: Map[String, String]): Either[String, Config] =
    for {
      velocityMax           <- int(env, "VELOCITY_MAX", 5)
      velocityWindowSeconds <- int(env, "VELOCITY_WINDOW_SECONDS", 10)
      alertDedupTtlSeconds  <- int(env, "ALERT_DEDUP_TTL_SECONDS", 86400)
    } yield Config(
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw"),
      env.getOrElse("GROUP_ID", "redis-jobs"),
      env.getOrElse("DLQ_TOPIC", "transactions.dlq"),
      env.getOrElse("ALERTS_TOPIC", "fraud.alerts"),
      env.getOrElse("REDIS_URI", "redis://localhost:6379"),
      velocityMax,
      velocityWindowSeconds.seconds,
      list(env, "BLOCKED_CARDS", ""),
      list(env, "BLOCKED_MERCHANTS", "m-999"),
      alertDedupTtlSeconds.seconds
    )

  private def list(env: Map[String, String], key: String, default: String): Set[String] =
    env.getOrElse(key, default).split(',').map(_.trim).filter(_.nonEmpty).toSet

  private def int(env: Map[String, String], key: String, default: Int): Either[String, Int] =
    env.get(key) match {
      case None      => Right(default)
      case Some(raw) => raw.toIntOption.toRight(s"$key must be a whole number, got '$raw'")
    }
}
