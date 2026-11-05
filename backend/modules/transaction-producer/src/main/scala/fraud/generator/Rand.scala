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

  def between(min: Long, max: Long): Rand[Long] = {
    // bit manipulation to avoid cycles in the generated output. this is largely caused by the seed values in nextLong
    nextLong.map(n => min + Math.floorMod(n >>> 32, max - min))
  }

  // picking a random index from an array of any items (could be enum labels) (remember vectors are fast sequences)
  def oneOf[A](items: Vector[A]): Rand[A] =
    between(0, items.size).map(i => items(i.toInt))

  // the pure function, wraps the "base case" empty vector in Rand
  def unit[A](a: A): Rand[A] = new Rand[A] {
    def run(seed: Seed): (A, Seed) = (a, seed)
  }

  // recursive function building up n random values of type A
  // fill the stack with calls down to listOfN(0, r) then build up from Rand(empty vector) prepending all
  // random values generated along the way
  def listOfN[A](n: Int, r: Rand[A]): Rand[Vector[A]] =
  // base case described in unit
    if (n <= 0) unit(Vector.empty)
    else {
      // recursion to prepend the generated random value to the returned Rand(vector)
      for {
        a    <- r
        rest <- listOfN(n - 1, r)
      } yield a +: rest
    }
}
