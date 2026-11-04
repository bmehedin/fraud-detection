package fraud.spark

final case class Config(bootstrapServers: String, topic: String, startingOffsets: String, watermarkDelay: String, maxKmh: Double, checkpointDir: String)

object Config {

  def parse(env: Map[String, String]): Config =
    Config(
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw"),
      env.getOrElse("STARTING_OFFSETS", "latest"),
      env.getOrElse("WATERMARK_DELAY", "10 minutes"),
      env.getOrElse("MAX_SPEED_KMH", "900").toDouble,
      env.getOrElse("CHECKPOINT_DIR", "target/checkpoints/s2")
    )
}
