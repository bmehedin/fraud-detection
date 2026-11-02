package fraud.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, from_json}
import org.apache.spark.sql.types._

object StreamingMain {

  val transactionSchema: StructType = StructType(
    Seq(
      StructField("transactionId", StringType),
      StructField("cardId", StringType),
      StructField("merchantId", StringType),
      StructField("merchantCategory", StringType),
      StructField("amountMinor", LongType),
      StructField(
        "origin",
        StructType(
          Seq(
            StructField("type", StringType),
            StructField(
              "point",
              StructType(
                Seq(
                  StructField("lat", DoubleType),
                  StructField("lon", DoubleType),
                  StructField("country", StringType)
                )
              )
            ),
            StructField("ip", StringType)
          )
        )
      ),
      StructField("eventTime", TimestampType)
    )
  )

  def main(args: Array[String]): Unit = {
    val config = Config.parse(sys.env)

    val spark = SparkSession
      .builder()
      .appName("fraud-streaming")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    val transactions = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.bootstrapServers)
      .option("subscribe", config.topic)
      .option("startingOffsets", config.startingOffsets)
      .load()
      .select(col("partition"), col("offset"), from_json(col("value").cast("string"), transactionSchema).as("tx"))
      .select("partition", "offset", "tx.*")

    transactions.writeStream
      .format("console")
      .option("truncate", "false")
      .start()
      .awaitTermination()
  }
}
