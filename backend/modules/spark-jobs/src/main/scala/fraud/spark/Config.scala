package fraud.spark

final case class Config(bootstrapServers: String, topic: String, startingOffsets: String, watermarkDelay: String, maxKmh: Double, checkpointDir: String, alertsTopic: String, parquetDir: String, parquetCheckpointDir: String, parquetTrigger: String)

object Config {

  def parse(env: Map[String, String]): Config =
    Config(
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw"),
      env.getOrElse("STARTING_OFFSETS", "latest"),
      env.getOrElse("WATERMARK_DELAY", "10 minutes"),
      env.getOrElse("MAX_SPEED_KMH", "900").toDouble,
      env.getOrElse("CHECKPOINT_DIR", "target/checkpoints/s2"),
      env.getOrElse("ALERTS_TOPIC", "fraud.alerts"),
      env.getOrElse("PARQUET_DIR", "target/parquet/transactions"),
      env.getOrElse("PARQUET_CHECKPOINT_DIR", "target/checkpoints/parquet"),
      env.getOrElse("PARQUET_TRIGGER", "1 minute")
    )
}
