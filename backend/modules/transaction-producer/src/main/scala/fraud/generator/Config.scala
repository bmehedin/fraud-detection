package fraud.generator

final case class Config(
    rate: Int,
    cards: Int,
    fraudPercent: Int,
    seed: Int,
    output: String,
    bootstrapServers: String,
    topic: String
)

object Config {

  def parse(env: Map[String, String]): Either[String, Config] =
    for {
      rate         <- int(env, "RATE", 10)
      cards        <- int(env, "CARDS", 1000)
      fraudPercent <- int(env, "FRAUD_PERCENT", 1)
      seed         <- int(env, "SEED", 42)
    } yield Config(
      rate,
      cards,
      fraudPercent,
      seed,
      env.getOrElse("OUTPUT", "stdout"),
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw")
    )

  private def int(env: Map[String, String], key: String, default: Int): Either[String, Int] =
    env.get(key) match {
      case None      => Right(default)
      case Some(raw) => raw.toIntOption.toRight(s"$key must be a whole number, got '$raw'")
    }
}
