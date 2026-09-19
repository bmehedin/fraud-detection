package fraud.generator

final case class Seed(value: Long)

// this is basically the code contract defining the state monad (threading the state through chained calls)
// it says Rand[A] must implement run and its behaviour is to be implemented in accordance with A - it takes a Seed and returns (A, Seed)
// that is why map and flatMap define run - they each return a Rand[B] and Rand[B] must have run defined
// such that the output Rand[B] of map/flatMap calls can be run with the threaded seed
trait Rand[A] { outer =>

  def run(seed: Seed): (A, Seed)

  def map[B](f: A => B): Rand[B] = new Rand[B] {
    def run(seed: Seed): (B, Seed) = {
      val (a, nextSeed) = outer.run(seed)
      (f(a), nextSeed)
    }
  }

  def flatMap[B](f: A => Rand[B]): Rand[B] = new Rand[B] {
    def run(seed: Seed): (B, Seed) = {
      val (a, nextSeed) = outer.run(seed)
      f(a).run(nextSeed)
    }
  }
}


// the companion object defines randomiser behaviour for A = Long
// nextLong is a value defined as the Rand[Long] computation and must be run to actually generate the new Rand[Long] computation
object Rand {

  val nextLong: Rand[Long] = new Rand[Long] {
    def run(seed: Seed): (Long, Seed) = {
      val next = seed.value * 6364136223846793005L + 1442695040888963407L
      (next, Seed(next))
    }
  }
}
