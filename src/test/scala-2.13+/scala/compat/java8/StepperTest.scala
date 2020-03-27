/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.compat.java8

import java.util

import org.junit.Test
import org.junit.Assert._
import java.util.Spliterator

import collectionImpl._
import StreamConverters._
import scala.collection.{AnyStepper, IntStepper}


class IncStepperA(private val size0: Long) extends IntStepper {
  if (size0 < 0) throw new IllegalArgumentException("Size must be >= 0L")
  private var i = 0L
  def characteristics = Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED
  override def estimateSize: Long = math.max(0L, size0 - i)
  def hasStep = i < size0
  def nextStep() = { i += 1; (i - 1).toInt }
  def trySplit() = if (estimateSize <= 1) null else {
    val sub = new IncStepperA(size0 - (size0 - i)/2)
    sub.i = i
    i = sub.size0
    sub
  }
}

class IncSpliterator(private val size0: Long) extends Spliterator.OfInt {
  if (size0 < 0) throw new IllegalArgumentException("Size must be >= 0L")
  private var i = 0L
  def characteristics = Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED
  def estimateSize() = math.max(0L, size0 - i)
  def tryAdvance(f: java.util.function.IntConsumer): Boolean = if (i >= size0) false else { f.accept(i.toInt); i += 1; true }
  def trySplit(): Spliterator.OfInt = if (i+1 >= size0) null else {
    val sub = new IncSpliterator(size0 - (size0 - i)/2)
    sub.i = i
    i = sub.size0
    sub
  }
  override def forEachRemaining(f: java.util.function.IntConsumer): Unit = { while (i < size0) { f.accept(i.toInt); i += 1 } }
}

class MappingStepper[@specialized (Double, Int, Long) A, @specialized(Double, Int, Long) B](underlying: Stepper[A], mapping: A => B) extends Stepper[B] {
  def characteristics = underlying.characteristics
  def hasStep = underlying.hasStep
  def nextStep() = mapping(underlying.nextStep())

  override def trySplit(): Stepper[B] = {
    val r = underlying.trySplit()
    if (r == null) null else new MappingStepper[A, B](r, mapping)
  }

  override def estimateSize: Long = underlying.estimateSize

  override def javaIterator[C >: B]: util.Iterator[_] = new util.Iterator[B] {
    override def hasNext: Boolean = underlying.hasStep
    override def next(): B = mapping(underlying.nextStep())
  }
  def substep() = {
    val undersub = underlying.substep()
    if (undersub == null) null
    else new MappingStepper(undersub, mapping)
  }
  def spliterator[C >: B]: Spliterator[_] = new MappingSpliterator[A, B](underlying.spliterator.asInstanceOf[Spliterator[A]], mapping)
}

class MappingSpliterator[A, B](private val underlying: Spliterator[A], mapping: A => B) extends Spliterator[B] {
  def characteristics = underlying.characteristics
  def estimateSize() = underlying.estimateSize()
  def tryAdvance(f: java.util.function.Consumer[_ >: B]): Boolean = underlying.tryAdvance(new java.util.function.Consumer[A]{ def accept(a: A): Unit = { f.accept(mapping(a)) } })
  def trySplit(): Spliterator[B] = {
    val undersplit = underlying.trySplit()
    if (undersplit == null) null
    else new MappingSpliterator(undersplit, mapping)
  }
}
class IntToGenericSpliterator[A](private val underlying: Spliterator.OfInt, mapping: Int => A) extends Spliterator[A] {
  def characteristics = underlying.characteristics
  def estimateSize() = underlying.estimateSize()
  def tryAdvance(f: java.util.function.Consumer[_ >: A]): Boolean = underlying.tryAdvance(new java.util.function.IntConsumer{ def accept(a: Int): Unit = { f.accept(mapping(a)) } })
  def trySplit(): Spliterator[A] = {
    val undersplit = underlying.trySplit()
    if (undersplit == null) null
    else new IntToGenericSpliterator[A](undersplit, mapping)
  }
}
class IntToDoubleSpliterator(private val underlying: Spliterator.OfInt, mapping: Int => Double) extends Spliterator.OfDouble {
  def characteristics = underlying.characteristics
  def estimateSize() = underlying.estimateSize()
  def tryAdvance(f: java.util.function.DoubleConsumer): Boolean = underlying.tryAdvance(new java.util.function.IntConsumer{ def accept(a: Int): Unit = { f.accept(mapping(a)) } })
  def trySplit(): Spliterator.OfDouble = {
    val undersplit = underlying.trySplit()
    if (undersplit == null) null
    else new IntToDoubleSpliterator(undersplit, mapping)
  }
}
class IntToLongSpliterator(private val underlying: Spliterator.OfInt, mapping: Int => Long) extends Spliterator.OfLong {
  def characteristics = underlying.characteristics
  def estimateSize() = underlying.estimateSize()
  def tryAdvance(f: java.util.function.LongConsumer): Boolean = underlying.tryAdvance(new java.util.function.IntConsumer{ def accept(a: Int): Unit = { f.accept(mapping(a)) } })
  def trySplit(): Spliterator.OfLong = {
    val undersplit = underlying.trySplit()
    if (undersplit == null) null
    else new IntToLongSpliterator(undersplit, mapping)
  }
}

class SpliteratorStepper[A](sp: Spliterator[A]) extends AnyStepper[A] {
  override def trySplit(): AnyStepper[A] = {
    val r = sp.trySplit()
    if (r == null) null else new SpliteratorStepper(r)
  }

  var cache: AnyRef = null

  override def hasStep: Boolean = cache != null || sp.tryAdvance(x => cache = x.asInstanceOf[AnyRef])

  override def nextStep(): A = if (hasStep) {
    val r = cache
    cache = null
    r.asInstanceOf[A]
  } else throw new NoSuchElementException("")

  override def estimateSize: Long = sp.estimateSize()

  override def characteristics: Int = sp.characteristics()
}

class StepperTest {
  def subs[Z, A, CC <: Stepper[A]](zero: Z)(s: Stepper[A])(f: Stepper[A] => Z, op: (Z, Z) => Z): Z = {
    val ss = s.substep()
    if (ss == null) op(zero, f(s))
    else {
      val left = subs(zero)(ss)(f, op)
      subs(left)(s)(f, op)
    }
  }

  val sizes = Vector(0, 1, 2, 4, 15, 17, 2512)
  def sources: Vector[(Int, Stepper[Int])] = sizes.flatMap{ i =>
    Vector(
      i -> new IncStepperA(i),
      i -> new SpliteratorStepper(new IncSpliterator(i).asInstanceOf[Spliterator[Int]]),
      i -> new MappingStepper[Int,Int](new IncStepperA(i), x => x),
      i -> new MappingStepper[Long, Int](new SpliteratorStepper(new IntToLongSpliterator(new IncSpliterator(i), _.toLong).asInstanceOf[Spliterator[Long]]), _.toInt),
      i -> new MappingStepper[Double, Int](new SpliteratorStepper(new IntToDoubleSpliterator(new IncSpliterator(i), _.toDouble).asInstanceOf[Spliterator[Double]]), _.toInt),
      i -> new MappingStepper[String, Int](new SpliteratorStepper(new IntToGenericSpliterator[String](new IncSpliterator(i), _.toString)), _.toInt)
    )
  }

  @Test
  def stepping(): Unit = {
    sources.foreach{ case (i, s) => assert((0 until i).forall{ j => s.hasStep && s.nextStep() == j } && !s.hasStep) }
    sources.foreach{ case (i, s) => 
      val set = collection.mutable.BitSet.empty
      subs(0)(s)(
        { x => 
          while (x.hasStep) { val y = x.nextStep(); assert(!(set contains y)); set += y }
          0
        },
        _ + _
      )
      assert((0 until i).toSet == set)
    }
  }

  @Test
  def trying(): Unit = {
    sources.foreach{ case (i,s) => 
      val set = collection.mutable.BitSet.empty
      while (s.hasStep) { val y = s.nextStep(); assert(!(set contains y)); set += y }
      assert((0 until i).toSet == set)
    }
    sources.foreach{ case (i,s) =>
      val set = collection.mutable.BitSet.empty
      subs(0)(s)(
        { x =>
          while (x.hasStep) { val y = x.nextStep(); assert(!(set contains y)); set += y }
          0
        },
        _ + _
      )
      assertTrue(s.getClass.getName + s" said [0, $i) was " + set.mkString("{", " ", "}"), (0 until i).toSet == set)
    }
  }

  @Test
  def substepping(): Unit = {
    sources.foreach{ case (i,s) =>
      val ss = s.substep()
      assertEquals(ss == null, i < 2)
      if (ss != null) {
        assertTrue(s.hasStep)
        assertTrue(ss.hasStep)
        val c1 = s.count()
        val c2 = ss.count()
        assertEquals(s"$i != $c1 + $c2 from ${s.getClass.getName}", i, c1 + c2)
      }
      else assertEquals(i, s.count())
    }
  }

  @Test
  def characteristically(): Unit = {
    val expected = Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED
    sources.foreach{ case (_,s) => assertEquals(s.characteristics, expected)}
    sources.foreach{ case (_,s) => subs(0)(s)(x => { assertEquals(x.characteristics, expected); 0 }, _ + _) }
  }

  @Test
  def count_only(): Unit = {
    sources.foreach{ case (i, s) => assertEquals(i, s.count()) }
    sources.foreach{ case (i, s) => assertEquals(i, subs(0)(s)(_.count().toInt, _ + _)) }
  }

  @Test
  def count_conditionally(): Unit = {
    sources.foreach{ case (i, s) => assertEquals((0 until i).count(_ % 3 == 0), s.count(_ % 3 == 0)) }
    sources.foreach{ case (i, s) => assertEquals((0 until i).count(_ % 3 == 0), subs(0)(s)(_.count(_ % 3 == 0).toInt, _ + _)) }
  }

  @Test
  def existence(): Unit = {
    sources.foreach{ case (i, s) => assert(i > 0 == s.exists(_ >= 0)) }
    sources.foreach{ case (i, s) => assert(i > 16 == s.exists(_ % 17 == 16)) }
    sources.foreach{ case (i, s) => assert(i > 0 == subs(false)(s)(_.exists(_ >= 0), _ || _)) }
    sources.foreach{ case (i, s) => assert(i > 16 == subs(false)(s)(_.exists(_ % 17 == 16), _ || _)) }
  }

  @Test
  def finding(): Unit = {
    for (k <- 0 until 100) {
      (sources zip sources).foreach{ case ((i,s), (j,t)) =>
        val x = scala.util.Random.nextInt(math.min(i,j)+3)
        val a = s.find(_ == x)
        val b = subs(None: Option[Int])(t)(_.find(_ == x), _ orElse _)
        assertEquals(a, b)
        assertEquals(a.isDefined, x < math.min(i,j))
      }      
    }
  }

  @Test
  def folding(): Unit = {
    sources.foreach{ case (i,s) => assertEquals((0 until i).mkString, s.fold("")(_ + _.toString)) }
    sources.foreach{ case (i,s) => assertEquals((0 until i).mkString, subs("")(s)(_.fold("")(_ + _.toString), _ + _)) }
    sources.foreach{ case (i,s) => assertEquals((0 until i).map(_.toDouble).sum, s.fold(0.0)(_ + _), 1e-10) }
    sources.foreach{ case (i,s) => assertEquals((0 until i).map(_.toDouble).sum, subs(0.0)(s)(_.fold(0.0)(_ + _), _ + _), 1e-10) }
  }

  @Test
  def foldingUntil(): Unit = {
    def expected(i: Int) = (0 until i).scan(0)(_ + _).dropWhile(_ < 6*i).headOption.getOrElse((0 until i).sum)
    sources.foreach{ case (i,s) => assertEquals(expected(i), s.foldTo(0)(_ + _)(_ >= 6*i)) }
    sources.foreach{ case (_,s) => assertEquals(-1, s.foldTo(-1)(_ * _)(_ => true)) }
    sources.foreach{ case (i,s) =>
      val ss = s.substep()
      val x = s.foldTo( if (ss == null) 0 else ss.foldTo(0)(_ + _)(_ >= 6*i) )(_ + _)(_ >= 6*i)
      assertEquals(expected(i), x)
    }
  }

  @Test
  def foreaching(): Unit = {
    sources.foreach{ case (i,s) =>
      val clq = new java.util.concurrent.ConcurrentLinkedQueue[String]
      s.foreach( clq add _.toString )
      assertEquals((0 until i).map(_.toString).toSet, Iterator.continually(if (!clq.isEmpty) Some(clq.poll) else None).takeWhile(_.isDefined).toSet.flatten)
    }
    sources.foreach{ case (i,s) =>
      val clq = new java.util.concurrent.ConcurrentLinkedQueue[String]
      subs(())(s)(_.foreach( clq add _.toString ), (_, _) => ())
      assertEquals((0 until i).map(_.toString).toSet, Iterator.continually(if (!clq.isEmpty) Some(clq.poll) else None).takeWhile(_.isDefined).toSet.flatten)
    }
  }

  @Test
  def reducing(): Unit = {
    sources.foreach{ case (i,s) => 
      if (i==0) assertEquals(s.hasStep, false)
      else assertEquals((0 until i).sum, s.reduce(_ + _))
    }
    sources.foreach{ case (i,s) =>
      assertEquals((0 until i).sum, subs(0)(s)(x => if (!x.hasStep) 0 else x.reduce(_ + _), _ + _))
    }
  }

  @Test
  def iterating(): Unit = {
    sources.foreach{ case (i, s) => assert(Iterator.range(0,i) sameElements s.iterator) }
  }

  @Test
  def spliterating(): Unit = {
    sources.foreach{ case (i,s) => 
      var sum = 0
      s.spliterator.asInstanceOf[Spliterator[Int]].forEachRemaining(new java.util.function.Consumer[Int]{ def accept(i: Int): Unit = { sum += i } })
      assertEquals(sum, (0 until i).sum)
    }
    sources.foreach{ case (i,s) => 
      val sum = subs(0)(s)(x => { var sm = 0; x.spliterator.asInstanceOf[Spliterator[Int]].forEachRemaining(new java.util.function.Consumer[Int]{ def accept(i: Int): Unit = { sm += i } }); sm }, _ + _)
      assertEquals(sum, (0 until i).sum)
    }
  }
}

