package fraud.spark

final case class Config(bootstrapServers: String, topic: String, startingOffsets: String)

object Config {

  def parse(env: Map[String, String]): Config =
    Config(
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw"),
      env.getOrElse("STARTING_OFFSETS", "latest")
    )
}
