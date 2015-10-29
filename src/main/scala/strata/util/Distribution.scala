package strata.util

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

/**
 * Helper to visualize distributions.
 */
case class Distribution(data: Seq[Long]) {

  def histogram(nBins: Int): String = {
    val (min, max) = (data.min, data.max)
    val realNBins = if (max - min + 1 < nBins) {
      (max - min + 1).toInt
    } else {
      nBins
    }
    val binSize = (max - min + 1).toDouble / realNBins.toDouble

    var upperBound = min + binSize
    var n = 0
    var i = 0
    val listB = ArrayBuffer[Int]()
    for (d <- data.sorted) {
      if (d < upperBound) {
        n += 1
      } else {
        while (d >= upperBound) {
          listB.append(n)
          n = 0
          upperBound += binSize
        }
        n += 1
      }
    }
    listB.append(n)

    val list = listB.toArray
    val maxN = list.max
    val width = 40
    val boundsChars = max.toString.length
    (for (i <- 0 to (realNBins - 1)) yield {
      val n = list(i)
      val lower = (min + i * binSize).floor.toInt
      val upper = (min + (i + 1) * binSize).floor.toInt
      val lowerStr = if (i == 0) " " * (boundsChars + 2)
      else (" " * (boundsChars - lower.toString.length)) + lower.toString + " ≤"
      val upperStr = if (i == realNBins - 1) " " * (boundsChars + 2)
      else "< " + upper.toString + (" " * (boundsChars - upper.toString.length))
      val thingies = "#" * (n / maxN.toDouble * width).round.toInt
      val boundStr = if (i != 0 && i != realNBins - 1 && lower == upper - 1) {
        (" " * (boundsChars + 2)) + " i = " + lower.toString + (" " * (boundsChars - lower.toString.length))
      } else {
        lowerStr + " i " + upperStr
      }

      boundStr + ": " + thingies + (" " * (width - thingies.length)) + s" ($n)"
    }).mkString("\n")
  }

  def info(title: String): String = {
    if (data.isEmpty) {
      s"Distribution of $title is empty"
    } else {
      s"Distribution of $title\n" +
        histogram(15) + "\n" +
        s"Total elements: ${data.length}\n" +
        s"Minimum:        ${data.min}\n" +
        s"Median:         ${median(data)}\n" +
        s"Maximum:        ${data.max}\n"
    }
  }

  private def median(arr: Seq[Long]) = {
    arr.sorted.apply((arr.size - 1) / 2)
  }

}
