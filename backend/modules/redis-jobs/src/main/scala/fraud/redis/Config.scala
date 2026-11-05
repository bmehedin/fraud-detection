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
    velocityWindow: FiniteDuration
)

object Config {

  def parse(env: Map[String, String]): Either[String, Config] =
    for {
      velocityMax           <- int(env, "VELOCITY_MAX", 5)
      velocityWindowSeconds <- int(env, "VELOCITY_WINDOW_SECONDS", 10)
    } yield Config(
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw"),
      env.getOrElse("GROUP_ID", "redis-jobs"),
      env.getOrElse("DLQ_TOPIC", "transactions.dlq"),
      env.getOrElse("ALERTS_TOPIC", "fraud.alerts"),
      env.getOrElse("REDIS_URI", "redis://localhost:6379"),
      velocityMax,
      velocityWindowSeconds.seconds
    )

  private def int(env: Map[String, String], key: String, default: Int): Either[String, Int] =
    env.get(key) match {
      case None      => Right(default)
      case Some(raw) => raw.toIntOption.toRight(s"$key must be a whole number, got '$raw'")
    }
}
