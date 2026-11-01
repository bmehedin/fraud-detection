package fraud.consumer

final case class Config(bootstrapServers: String, topic: String, groupId: String, dlqTopic: String)

object Config {

  def parse(env: Map[String, String]): Config =
    Config(
      env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      env.getOrElse("TOPIC", "transactions.raw"),
      env.getOrElse("GROUP_ID", "transaction-consumer"),
      env.getOrElse("DLQ_TOPIC", "transactions.dlq")
    )
}
