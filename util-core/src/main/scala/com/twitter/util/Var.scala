package com.twitter.util

import java.util.concurrent.atomic.AtomicReference
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.collection.mutable

/**
 * Trait Var represents a variable. It is a reference cell which is
 * composable: dependent Vars (derived through flatMap) are
 * recomputed automatically when independent variables change -- they
 * implement a form of self-adjusting computation.
 *
 * Vars are observed, notifying users whenever the variable changes.
 *
 * @note Vars do not always perform the minimum amount of
 * re-computation.
 *
 * @note There are no well-defined error semantics for Var. Vars are
 * computed lazily, and the updating thread will receive any
 * exceptions thrown while computing derived Vars.
 */
trait Var[+T] { self =>
  import Var.Observer

  /** 
   * Observe this Var. `f` is invoked each time the variable changes,
   * and synchronously with the first call to this method.
   */
  final def observe(f: T => Unit): Closable = observe(0, Observer(f))

  /**
   * Concrete implementations of Var implement observe. This is
   * called for each toplevel observe. Depths indicate the relative
   * structural depth of the observation, from the frame of reference
   * of the root call to observe. (Each Var derived via flatMap
   * increases the depth.) Depths are used to order the invocation of
   * update callbacks. This is used to ensure that updates proceed in
   * topological order so that every input variable is fully resolved
   * before recomputing a derived variable.
   */
  protected def observe(depth: Int, obs: Observer[T]): Closable

  /** Synonymous with observe */
  def foreach(f: T => Unit) = observe(f)

  /** 
   * Create a derived variable by applying `f` to the contained
   * value.
   */
  def map[U](f: T => U): Var[U] = flatMap(t => Var.value(f(t)))

  /**
   * Create a dependent Var which behaves as `f` applied to the
   * current value of this Var. FlatMap manages a dynamic dependency
   * graph: the dependent Var is detached and recomputed  whenever
   * the outer Var changes, but only if there are any observers.  An
   * unobserved Var returned by flatMap will not invoke `f`
   */
  def flatMap[U](f: T => Var[U]): Var[U] = new Var[U] {
    def observe(depth: Int, obs: Observer[U]) = {
      val inner = new AtomicReference(Closable.nop)
      val outer = self.observe(depth, Observer(t =>
        inner.getAndSet(f(t).observe(depth+1, obs)).close()
      ))

      Closable.sequence(
        outer,
        Closable.make { deadline =>
          inner.getAndSet(Closable.nop).close(deadline)
        }
      )
    }
  }

  /**
   * Observe this Var into the given AtomicReference.
   * Observation stops when the returned closable is closed.
   */
  def observeTo[U >: T](ref: AtomicReference[U]): Closable =
    this observe { newv => ref.set(newv) }

  /**
   * A one-shot predicate observation. The returned future
   * is satisfied with the first observed value of Var that obtains
   * the predicate `pred`. Observation stops when the future is 
   * satisfied.
   *
   * Interrupting the future will also satisfy the future (with the
   * interrupt exception) and close the observation.
   */
  def observeUntil(pred: T => Boolean): Future[T] = {
    val p = Promise[T]()
    p.setInterruptHandler {
      case exc => p.updateIfEmpty(Throw(exc))
    }
    
    val o = observe { 
      case el if pred(el) => p.updateIfEmpty(Return(el))
      case _ => 
    }

    p ensure {
      o.close()
    }
  }
}

object Var {
  /**
   * A Var observer. Observers are owned by exactly one producer,
   * enforced by a leasing mechanism. Updates are propagated only
   * when the lease is valid.
   *
   * Note: The API is awkward and subtle, but happily limited.
   * Ownership must overlap in order for Vars to not miss updates:
   * the handover process is for the new owner to call 'lease' before
   * the previous observation is closed.
   */
  private[util] class Observer[-T](o: T => Unit) {
    private[this] var owner: Object = null
    
    /**
     * Lease this observer. Returns true when this represents a new
     * owner.
     */
    def lease(who: Object): Boolean = synchronized {
      val newOwner = owner ne who
      owner = who
      newOwner
    }
    
    /**
     * Release the lease possibly held by who.
     */
    def relinquish(who: Object): Unit = synchronized {
      if (owner eq who)
        owner = null
    }
    
    /**
     * Update the observer, conditionally on who being 
     * the current lease holder.
     */
    def update(newt: T, who: Object): Unit = synchronized {
      if (owner eq who)
        o(newt)
    }
  }

  private[util] object Observer {
    def apply[T](k: T => Unit) = new Observer(k)
  }

  /**
   * Sample the current value of this Var. Note that this may lead to
   * surprising results for lazily defined Vars: the act of observing
   * a Var may be kick off a process to populate it; the value
   * returned from sample may then reflect an intermediate value.
   */
  def sample[T](v: Var[T]): T = {
    var opt: Option[T] = None
    v.observe(v => opt = Some(v)).close()
    opt.get
  }
  
  object Sampled {
    def apply[T](v: T): Var[T] = value(v)
    def unapply[T](v: Var[T]): Option[T] = Some(sample(v))
  }

  /**
   * Create a new, updatable Var with an initial value. We call
   * such Vars independent -- derived Vars being dependent
   * on these.
   */
  def apply[T](init: T): Var[T] with Updatable[T] with Extractable[T] =
    new UpdatableVar(init)

  /**
   * Create a new, constant, v-valued Var.
   */
  def value[T](v: T): Var[T] = new Var[T] {
    // We maintain a map of an observer's current closer for this
    // Var. This allows us to make sure that we own an observer
    // exactly once; overlapping observations will replace the
    // current closable.
    private[this] val observers = mutable.Map[Observer[T], Closable]()

    private[this] def newCloser(obs: Observer[T]) = new Closable {
      def close(deadline: Time) = Var.this.synchronized {
        observers.get(obs) match {
          case Some(closer) if closer eq this =>
            observers -= obs
            obs.relinquish(Var.this)
          case _ =>
        }

        Future.Done
      }
    }

    protected def observe(depth: Int, obs: Observer[T]): Closable = synchronized {
      if (obs.lease(this))
        obs.update(v, this)

      val closer = newCloser(obs)
      observers(obs) = closer
      closer
    }
  }

  /** 
   * Collect a collection of Vars into a Var of collection.
   */
  def collect[T, CC[X] <: Traversable[X]](vars: CC[Var[T]])
      (implicit newBuilder: CanBuildFrom[CC[T], T, CC[T]], cm: ClassManifest[T])
      : Var[CC[T]] = async(newBuilder().result) { v =>
    val N = vars.size
    val cur = new Array[T](N)
    var filling = true
    def build() = {
      val b = newBuilder()
      b ++= cur
      b.result()
    }

    def publish(i: Int, newi: T) = synchronized {
      cur(i) = newi
      if (!filling) v() = build()
    }

    val closes = new Array[Closable](N)
    var i = 0
    for (u <- vars) {
      val j = i
      closes(j) = u observe { newj => publish(j, newj) }
      i += 1
    }

    synchronized {
      filling = false
      v() = build()
    }

    Closable.all(closes:_*)
  }

  private object create {
    sealed trait State[+T]
    object Idle extends State[Nothing]
    case class Observing[T](n: Int, v: Var[T], c: Closable) extends State[T]
  }

  /**
   * Create a new Var whose values are provided asynchronously by
   * `update`. The returned Var is dormant until it is observed:
   * `update` is called by-need. Such observations are also reference
   * counted so that simultaneous observervations do not result in
   * multiple invocations of `update`. When the last observer stops
   * observing, the [[com.twitter.util.Closable]] returned
   * from `update` is closed. Subsequent observations result in a new
   * call to `update`.
   *
   * `empty` is used to fill the returned Var until `update` has
   * provided a value. The first observation of the returned Var is
   * synchronous with the call to `update`--it is guaranteed the the
   * opportunity to fill the Var before the observer sees any value
   * at all.
   *
   * Updates from `update` are ignored after the returned
   * [[com.twitter.util.Closable]] is closed.
   */
  def async[T](empty: T)(update: Updatable[T] => Closable): Var[T] = new Var[T] {
    import create._
    private var state: State[T] = Idle
    
    private val closable = Closable.make { deadline =>
      synchronized {
        state match {
          case Idle =>
            Future.Done
          case Observing(1, _, c) =>
            state = Idle
            c.close(deadline)
          case Observing(n, v, c) =>
            state = Observing(n-1, v, c)
            Future.Done
        }
      }
    }

    protected def observe(depth: Int, obs: Observer[T]): Closable = {
      val v = synchronized {
        state match {
          case Idle =>
            val v = Var(empty)
            val c = update(v)
            state = Observing(1, v, c)
            v
          case Observing(n, v, c) =>
            state = Observing(n+1, v, c)
            v
        }
      }

      val c = v.observe(depth, obs)
      Closable.sequence(c, closable)
    }
  }
}

/** Denotes an updatable container. */
trait Updatable[T] {
  /** Update the container with value `t` */
  def update(t: T)
}

trait Extractable[T] {
  def apply(): T
}

private object UpdatableVar {
  import Var.Observer

  case class O[T](
      obs: Observer[T],
      depth: Int,
      version: Long
  )

  implicit def order[T] = new Ordering[O[T]] {
    // This is safe because observers are compared
    // only from the same source of versions.
    def compare(a: O[T], b: O[T]): Int = {
      val c1 = a.depth compare b.depth
      if (c1 != 0) return c1
      a.version compare b.version
    }
  }
}

private class UpdatableVar[T](init: T) 
    extends Var[T] 
    with Updatable[T] 
    with Extractable[T] {
  import UpdatableVar._
  import Var.Observer

  @volatile protected var value: T = init
  private[this] var version = 0L
  private[this] var observers = 
    immutable.SortedSet.empty[O[T]]

  def apply(): T = value

  def update(t: T): Unit = synchronized {
    if (value == t) return
    value = t
    for (O(obs, _, _) <- observers)
      obs.update(t, this)
  }

  protected def observe(depth: Int, obs: Observer[T]): Closable = synchronized {
    val newOwner = obs.lease(this)
    if (!newOwner) {
      val Some(old) = observers.find(_.obs eq obs)
      observers -= old
    }

    version += 1
    val o = O(obs, depth, version)
    observers += o

    if (newOwner)
      obs.update(value, this)

    newCloser(o)
  }

  private[this] def newCloser(o: O[T]) = new Closable {
    def close(deadline: Time) = UpdatableVar.this.synchronized {
      if (observers contains o) {
        observers -= o
        o.obs.relinquish(UpdatableVar.this)
      }
      Future.Done
    }
  }

  override def toString = "Var("+value+")"
}
