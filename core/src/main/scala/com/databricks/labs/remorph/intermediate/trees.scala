package com.databricks.labs.remorph.intermediate

import com.databricks.labs.remorph.utils.Strings.truncatedString
import com.fasterxml.jackson.annotation.JsonIgnore

import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** Used by [[TreeNode.getNodeNumbered]] when traversing the tree for a given number */
private class MutableInt(var i: Int)

case class Origin(
    line: Option[Int] = None,
    startPosition: Option[Int] = None,
    startIndex: Option[Int] = None,
    stopIndex: Option[Int] = None,
    sqlText: Option[String] = None,
    objectType: Option[String] = None,
    objectName: Option[String] = None)

object CurrentOrigin {
  private val value = new ThreadLocal[Origin]() {
    override def initialValue: Origin = Origin()
  }

  def get: Origin = value.get()

  def setPosition(line: Int, start: Int): Unit = {
    value.set(value.get.copy(line = Some(line), startPosition = Some(start)))
  }

  def withOrigin[A](o: Origin)(f: => A): A = {
    set(o)
    val ret =
      try f
      finally { reset() }
    ret
  }

  def set(o: Origin): Unit = value.set(o)

  def reset(): Unit = value.set(Origin())
}

class TreeNodeException[TreeType <: TreeNode[_]](@transient val tree: TreeType, msg: String, cause: Throwable)
    extends Exception(msg, cause) {

  val treeString = tree.toString

  // Yes, this is the same as a default parameter, but... those don't seem to work with SBT
  // external project dependencies for some reason.
  def this(tree: TreeType, msg: String) = this(tree, msg, null)

  override def getMessage: String = {
    s"${super.getMessage}, tree:${if (treeString contains "\n") "\n" else " "}$tree"
  }
}

// scalastyle:off
abstract class TreeNode[BaseType <: TreeNode[BaseType]] extends Product {
  // scalastyle:on
  self: BaseType =>

  @JsonIgnore lazy val containsChild: Set[TreeNode[_]] = children.toSet
  private lazy val _hashCode: Int = productHash(this, scala.util.hashing.MurmurHash3.productSeed)
  private lazy val allChildren: Set[TreeNode[_]] = (children ++ innerChildren).toSet[TreeNode[_]]
  @JsonIgnore val origin: Origin = CurrentOrigin.get

  /**
   * Returns a Seq of the children of this node. Children should not change. Immutability required for containsChild
   * optimization
   */
  def children: Seq[BaseType]

  override def hashCode(): Int = _hashCode

  /**
   * Faster version of equality which short-circuits when two treeNodes are the same instance. We don't just override
   * Object.equals, as doing so prevents the scala compiler from generating case class `equals` methods
   */
  def fastEquals(other: TreeNode[_]): Boolean = {
    this.eq(other) || this == other
  }

  /**
   * Find the first [[TreeNode]] that satisfies the condition specified by `f`. The condition is recursively applied to
   * this node and all of its children (pre-order).
   */
  def find(f: BaseType => Boolean): Option[BaseType] = if (f(this)) {
    Some(this)
  } else {
    children.foldLeft(Option.empty[BaseType]) { (l, r) => l.orElse(r.find(f)) }
  }

  /**
   * Runs the given function on this node and then recursively on [[children]].
   * @param f
   *   the function to be applied to each node in the tree.
   */
  def foreach(f: BaseType => Unit): Unit = {
    f(this)
    children.foreach(_.foreach(f))
  }

  /**
   * Runs the given function recursively on [[children]] then on this node.
   * @param f
   *   the function to be applied to each node in the tree.
   */
  def foreachUp(f: BaseType => Unit): Unit = {
    children.foreach(_.foreachUp(f))
    f(this)
  }

  /**
   * Returns a Seq containing the result of applying the given function to each node in this tree in a preorder
   * traversal.
   * @param f
   *   the function to be applied.
   */
  def map[A](f: BaseType => A): Seq[A] = {
    val ret = new collection.mutable.ArrayBuffer[A]()
    foreach(ret += f(_))
    ret.toList.toSeq
  }

  /**
   * Returns a Seq by applying a function to all nodes in this tree and using the elements of the resulting collections.
   */
  def flatMap[A](f: BaseType => TraversableOnce[A]): Seq[A] = {
    val ret = new collection.mutable.ArrayBuffer[A]()
    foreach(ret ++= f(_))
    ret.toList.toSeq
  }

  /**
   * Returns a Seq containing the result of applying a partial function to all elements in this tree on which the
   * function is defined.
   */
  def collect[B](pf: PartialFunction[BaseType, B]): Seq[B] = {
    val ret = new collection.mutable.ArrayBuffer[B]()
    val lifted = pf.lift
    foreach(node => lifted(node).foreach(ret.+=))
    ret.toList.toSeq
  }

  /**
   * Returns a Seq containing the leaves in this tree.
   */
  def collectLeaves(): Seq[BaseType] = {
    this.collect { case p if p.children.isEmpty => p }
  }

  /**
   * Finds and returns the first [[TreeNode]] of the tree for which the given partial function is defined (pre-order),
   * and applies the partial function to it.
   */
  def collectFirst[B](pf: PartialFunction[BaseType, B]): Option[B] = {
    val lifted = pf.lift
    lifted(this).orElse {
      children.foldLeft(Option.empty[B]) { (l, r) => l.orElse(r.collectFirst(pf)) }
    }
  }

  /**
   * Returns a copy of this node with the children replaced. TODO: Validate somewhere (in debug mode?) that children are
   * ordered correctly.
   */
  def withNewChildren(newChildren: Seq[BaseType]): BaseType = {
    assert(newChildren.size == children.size, "Incorrect number of children")
    var changed = false
    val remainingNewChildren = newChildren.toBuffer
    val remainingOldChildren = children.toBuffer
    def mapTreeNode(node: TreeNode[_]): TreeNode[_] = {
      val newChild = remainingNewChildren.remove(0)
      val oldChild = remainingOldChildren.remove(0)
      if (newChild fastEquals oldChild) {
        oldChild
      } else {
        changed = true
        newChild
      }
    }
    def mapChild(child: Any): Any = child match {
      case arg: TreeNode[_] if containsChild(arg) => mapTreeNode(arg)
      // CaseWhen Case or any tuple type
      case (left, right) => (mapChild(left), mapChild(right))
      case nonChild: AnyRef => nonChild
      case null => null
    }
    val newArgs = mapProductIterator {
      case s: StructType => s // Don't convert struct types to some other type of Seq[StructField]
      // Handle Seq[TreeNode] in TreeNode parameters.
      case s: Stream[_] =>
        // Stream is lazy so we need to force materialization
        s.map(mapChild).force
      case s: Seq[_] =>
        s.map(mapChild)
      case m: Map[_, _] =>
        // `map.mapValues().view.force` return `Map` in Scala 2.12 but return `IndexedSeq` in Scala
        // 2.13, call `toMap` method manually to compatible with Scala 2.12 and Scala 2.13
        // `mapValues` is lazy and we need to force it to materialize
        m.mapValues(mapChild).view.force.toMap
      case arg: TreeNode[_] if containsChild(arg) => mapTreeNode(arg)
      case Some(child) => Some(mapChild(child))
      case nonChild: AnyRef => nonChild
      case null => null
    }

    if (changed) makeCopy(newArgs) else this
  }

  /**
   * Efficient alternative to `productIterator.map(f).toArray`.
   */
  protected def mapProductIterator[B: ClassTag](f: Any => B): Array[B] = {
    val arr = Array.ofDim[B](productArity)
    var i = 0
    while (i < arr.length) {
      arr(i) = f(productElement(i))
      i += 1
    }
    arr
  }

  /**
   * Creates a copy of this type of tree node after a transformation. Must be overridden by child classes that have
   * constructor arguments that are not present in the productIterator.
   * @param newArgs
   *   the new product arguments.
   */
  def makeCopy(newArgs: Array[AnyRef]): BaseType = makeCopy(newArgs, allowEmptyArgs = false)

  /**
   * Creates a copy of this type of tree node after a transformation. Must be overridden by child classes that have
   * constructor arguments that are not present in the productIterator.
   * @param newArgs
   *   the new product arguments.
   * @param allowEmptyArgs
   *   whether to allow argument list to be empty.
   */
  private def makeCopy(newArgs: Array[AnyRef], allowEmptyArgs: Boolean): BaseType = attachTree(this, "makeCopy") {
    val allCtors = getClass.getConstructors
    if (newArgs.isEmpty && allCtors.isEmpty) {
      // This is a singleton object which doesn't have any constructor. Just return `this` as we
      // can't copy it.
      return this
    }

    // Skip no-arg constructors that are just there for kryo.
    val ctors = allCtors.filter(allowEmptyArgs || _.getParameterTypes.size != 0)
    if (ctors.isEmpty) {
      sys.error(s"No valid constructor for $nodeName")
    }
    val allArgs: Array[AnyRef] = if (otherCopyArgs.isEmpty) {
      newArgs
    } else {
      newArgs ++ otherCopyArgs
    }
    val defaultCtor = ctors
      .find { ctor =>
        if (ctor.getParameterTypes.length != allArgs.length) {
          false
        } else if (allArgs.contains(null)) {
          // if there is a `null`, we can't figure out the class, therefore we should just fallback
          // to older heuristic
          false
        } else {
          val argsArray: Array[Class[_]] = allArgs.map(_.getClass)
          isAssignable(argsArray, ctor.getParameterTypes)
        }
      }
      .getOrElse(ctors.maxBy(_.getParameterTypes.length)) // fall back to older heuristic

    try {
      CurrentOrigin.withOrigin(origin) {
        val res = defaultCtor.newInstance(allArgs.toArray: _*).asInstanceOf[BaseType]
        res
      }
    } catch {
      case e: java.lang.IllegalArgumentException =>
        throw new TreeNodeException(
          this,
          s"""
             |Failed to copy node.
             |Is otherCopyArgs specified correctly for $nodeName.
             |Exception message: ${e.getMessage}
             |ctor: $defaultCtor?
             |types: ${newArgs.map(_.getClass).mkString(", ")}
             |args: ${newArgs.mkString(", ")}
           """.stripMargin)
    }
  }

  /**
   * Wraps any exceptions that are thrown while executing `f` in a [[TreeNodeException]], attaching the provided `tree`.
   */
  def attachTree[TreeType <: TreeNode[_], A](tree: TreeType, msg: String = "")(f: => A): A = {
    try f
    catch {
      // difference from the original code: we are not checking for SparkException
      case NonFatal(e) =>
        throw new TreeNodeException(tree, msg, e)
    }
  }

  /**
   * Args to the constructor that should be copied, but not transformed. These are appended to the transformed args
   * automatically by makeCopy
   * @return
   */
  protected def otherCopyArgs: Seq[AnyRef] = Nil

  /**
   * Simplified version compared to commons-lang3
   * @param classArray
   *   the class array
   * @param toClassArray
   *   the class array to check against
   * @return
   *   true if the classArray is assignable to toClassArray
   */
  private def isAssignable(classArray: Array[Class[_]], toClassArray: Array[Class[_]]): Boolean = {
    if (classArray.length != toClassArray.length) {
      false
    } else {
      classArray.zip(toClassArray).forall { case (c, toC) =>
        c.isPrimitive match {
          case true => c == toC
          case false => toC.isAssignableFrom(c)
        }
      }
    }
  }

  /**
   * Returns the name of this type of TreeNode. Defaults to the class name. Note that we remove the "Exec" suffix for
   * physical operators here.
   */
  def nodeName: String = simpleClassName.replaceAll("Exec$", "")

  private def simpleClassName: String = try {
    this.getClass.getSimpleName
  } catch {
    case _: InternalError =>
      val name = this.getClass.getName
      val dollar = name.lastIndexOf('$')
      if (dollar == -1) name else name.substring(0, dollar)
  }

  /**
   * Returns a copy of this node where `rule` has been recursively applied to the tree. When `rule` does not apply to a
   * given node it is left unchanged. Users should not expect a specific directionality. If a specific directionality is
   * needed, transformDown or transformUp should be used.
   *
   * @param rule
   *   the function use to transform this nodes children
   */
  def transform(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    transformDown(rule)
  }

  /**
   * Returns a copy of this node where `rule` has been recursively applied to it and all of its children (pre-order).
   * When `rule` does not apply to a given node it is left unchanged.
   *
   * @param rule
   *   the function used to transform this nodes children
   */
  def transformDown(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    val afterRule = CurrentOrigin.withOrigin(origin) {
      rule.applyOrElse(this, identity[BaseType])
    }

    // Check if unchanged and then possibly return old copy to avoid gc churn.
    if (this fastEquals afterRule) {
      mapChildren(_.transformDown(rule))
    } else {
      // If the transform function replaces this node with a new one, carry over the tags.
      afterRule.mapChildren(_.transformDown(rule))
    }
  }

  /**
   * Returns a copy of this node where `rule` has been recursively applied first to all of its children and then itself
   * (post-order). When `rule` does not apply to a given node, it is left unchanged.
   *
   * @param rule
   *   the function use to transform this nodes children
   */
  def transformUp(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    val afterRuleOnChildren = mapChildren(_.transformUp(rule))
    val newNode = if (this fastEquals afterRuleOnChildren) {
      CurrentOrigin.withOrigin(origin) {
        rule.applyOrElse(this, identity[BaseType])
      }
    } else {
      CurrentOrigin.withOrigin(origin) {
        rule.applyOrElse(afterRuleOnChildren, identity[BaseType])
      }
    }
    // If the transform function replaces this node with a new one, carry over the tags.
    newNode
  }

  /**
   * Returns a copy of this node where `f` has been applied to all the nodes in `children`.
   */
  def mapChildren(f: BaseType => BaseType): BaseType = {
    if (containsChild.nonEmpty) {
      mapChildren(f, forceCopy = false)
    } else {
      this
    }
  }

  override def clone(): BaseType = {
    mapChildren(_.clone(), forceCopy = true)
  }

  /** Returns a string representing the arguments to this node, minus any children */
  def argString(maxFields: Int): String = stringArgs
    .flatMap {
      case tn: TreeNode[_] if allChildren.contains(tn) => Nil
      case Some(tn: TreeNode[_]) if allChildren.contains(tn) => Nil
      case Some(tn: TreeNode[_]) => tn.simpleString(maxFields) :: Nil
      case tn: TreeNode[_] => tn.simpleString(maxFields) :: Nil
      case seq: Seq[Any] if seq.toSet.subsetOf(allChildren.asInstanceOf[Set[Any]]) => Nil
      case iter: Iterable[_] if iter.isEmpty => Nil
      case seq: Seq[_] => truncatedString(seq, "[", ", ", "]", maxFields) :: Nil
      case set: Set[_] => truncatedString(set.toSeq, "{", ", ", "}", maxFields) :: Nil
      case array: Array[_] if array.isEmpty => Nil
      case array: Array[_] => truncatedString(array, "[", ", ", "]", maxFields) :: Nil
      case null => Nil
      case None => Nil
      case Some(null) => Nil
      case Some(any) => any :: Nil
      case map: Map[_, _] =>
        redactMapString(map, maxFields)
      case table: CatalogTable =>
        table.identifier :: Nil
      case other => other :: Nil
    }
    .mkString(", ")

  /**
   * ONE line description of this node.
   * @param maxFields
   *   Maximum number of fields that will be converted to strings. Any elements beyond the limit will be dropped.
   */
  def simpleString(maxFields: Int): String = s"$nodeName ${argString(maxFields)}".trim

  override def toString: String = treeString.replaceAll("\n]\n", "]\n") // TODO: fix properly

  /** Returns a string representation of the nodes in this tree */
  final def treeString: String = treeString()

  final def treeString(maxFields: Int = 25): String = {
    val concat = new StringBuilder()
    treeString(str => concat.append(str), maxFields)
    concat.toString
  }

  def treeString(append: String => Unit, maxFields: Int): Unit = {
    generateTreeString(0, Nil, append, "", maxFields)
  }

  /**
   * Returns a string representation of the nodes in this tree, where each operator is numbered. The numbers can be used
   * with [[TreeNode.apply]] to easily access specific subtrees.
   *
   * The numbers are based on depth-first traversal of the tree (with innerChildren traversed first before children).
   */
  def numberedTreeString: String =
    treeString.split("\n").zipWithIndex.map { case (line, i) => f"$i%02d $line" }.mkString("\n")

  /**
   * Returns the tree node at the specified number, used primarily for interactive debugging. Numbers for each node can
   * be found in the [[numberedTreeString]].
   *
   * Note that this cannot return BaseType because logical plan's plan node might return physical plan for
   * innerChildren, e.g. in-memory child logical plan node has a reference to the physical plan node it is referencing.
   */
  def apply(number: Int): TreeNode[_] = getNodeNumbered(new MutableInt(number)).orNull

  /**
   * Returns the tree node at the specified number, used primarily for interactive debugging. Numbers for each node can
   * be found in the [[numberedTreeString]].
   *
   * This is a variant of [[apply]] that returns the node as BaseType (if the type matches).
   */
  def p(number: Int): BaseType = apply(number).asInstanceOf[BaseType]

  /**
   * All the nodes that should be shown as a inner nested tree of this node. For example, this can be used to show
   * sub-queries.
   */
  def innerChildren: Seq[TreeNode[_]] = Seq.empty

  /**
   * Returns a 'scala code' representation of this `TreeNode` and its children. Intended for use when debugging where
   * the prettier toString function is obfuscating the actual structure. In the case of 'pure' `TreeNodes` that only
   * contain primitives and other TreeNodes, the result can be pasted in the REPL to build an equivalent Tree.
   */
  def asCode: String = pprint.apply(self).plainText

  /**
   * The arguments that should be included in the arg string. Defaults to the `productIterator`.
   */
  protected def stringArgs: Iterator[Any] = productIterator

  // Copied from Scala 2.13.1
  // github.com/scala/scala/blob/v2.13.1/src/library/scala/util/hashing/MurmurHash3.scala#L56-L73
  // to prevent the issue https://github.com/scala/bug/issues/10495
  // TODO(SPARK-30848): Remove this once we drop Scala 2.12.
  private final def productHash(x: Product, seed: Int, ignorePrefix: Boolean = false): Int = {
    val arr = x.productArity
    // Case objects have the hashCode inlined directly into the
    // synthetic hashCode method, but this method should still give
    // a correct result if passed a case object.
    if (arr == 0) {
      x.productPrefix.hashCode
    } else {
      var h = seed
      if (!ignorePrefix) h = scala.util.hashing.MurmurHash3.mix(h, x.productPrefix.hashCode)
      var i = 0
      while (i < arr) {
        h = scala.util.hashing.MurmurHash3.mix(h, x.productElement(i).##)
        i += 1
      }
      scala.util.hashing.MurmurHash3.finalizeHash(h, arr)
    }
  }

  /**
   * Returns a copy of this node where `f` has been applied to all the nodes in `children`.
   * @param f
   *   The transform function to be applied on applicable `TreeNode` elements.
   * @param forceCopy
   *   Whether to force making a copy of the nodes even if no child has been changed.
   */
  private def mapChildren(f: BaseType => BaseType, forceCopy: Boolean): BaseType = {
    var changed = false

    def mapChild(child: Any): Any = child match {
      case arg: TreeNode[_] if containsChild(arg) =>
        val newChild = f(arg.asInstanceOf[BaseType])
        if (forceCopy || !(newChild fastEquals arg)) {
          changed = true
          newChild
        } else {
          arg
        }
      case tuple @ (arg1: TreeNode[_], arg2: TreeNode[_]) =>
        val newChild1 = if (containsChild(arg1)) {
          f(arg1.asInstanceOf[BaseType])
        } else {
          arg1.asInstanceOf[BaseType]
        }

        val newChild2 = if (containsChild(arg2)) {
          f(arg2.asInstanceOf[BaseType])
        } else {
          arg2.asInstanceOf[BaseType]
        }

        if (forceCopy || !(newChild1 fastEquals arg1) || !(newChild2 fastEquals arg2)) {
          changed = true
          (newChild1, newChild2)
        } else {
          tuple
        }
      case other => other
    }

    val newArgs = mapProductIterator {
      case arg: TreeNode[_] if containsChild(arg) =>
        val newChild = f(arg.asInstanceOf[BaseType])
        if (forceCopy || !(newChild fastEquals arg)) {
          changed = true
          newChild
        } else {
          arg
        }
      case Some(arg: TreeNode[_]) if containsChild(arg) =>
        val newChild = f(arg.asInstanceOf[BaseType])
        if (forceCopy || !(newChild fastEquals arg)) {
          changed = true
          Some(newChild)
        } else {
          Some(arg)
        }
      // `map.mapValues().view.force` return `Map` in Scala 2.12 but return `IndexedSeq` in Scala
      // 2.13, call `toMap` method manually to compatible with Scala 2.12 and Scala 2.13
      case m: Map[_, _] =>
        m.mapValues {
          case arg: TreeNode[_] if containsChild(arg) =>
            val newChild = f(arg.asInstanceOf[BaseType])
            if (forceCopy || !(newChild fastEquals arg)) {
              changed = true
              newChild
            } else {
              arg
            }
          case other => other
        }.view
          .force
          .toMap // `mapValues` is lazy and we need to force it to materialize
      case d: DataType => d // Avoid unpacking Structs
      case args: Stream[_] => args.map(mapChild).force // Force materialization on stream
      case args: Iterable[_] => args.map(mapChild)
      case nonChild: AnyRef => nonChild
      case null => null
    }
    if (forceCopy || changed) makeCopy(newArgs, forceCopy) else this
  }

  private def redactMapString[K, V](map: Map[K, V], maxFields: Int): List[String] = {
    // For security reason, redact the map value if the key is in centain patterns
    val redactedMap = map.toMap
    // construct the redacted map as strings of the format "key=value"
    val keyValuePairs = redactedMap.toSeq.map { item =>
      item._1 + "=" + item._2
    }
    truncatedString(keyValuePairs, "[", ", ", "]", maxFields) :: Nil
  }

  private def getNodeNumbered(number: MutableInt): Option[TreeNode[_]] = {
    if (number.i < 0) {
      None
    } else if (number.i == 0) {
      Some(this)
    } else {
      number.i -= 1
      // Note that this traversal order must be the same as numberedTreeString.
      innerChildren.map(_.getNodeNumbered(number)).find(_.isDefined).getOrElse {
        children.map(_.getNodeNumbered(number)).find(_.isDefined).flatten
      }
    }
  }

  /**
   * Appends the string representation of this node and its children to the given Writer.
   *
   * The `i`-th element in `lastChildren` indicates whether the ancestor of the current node at depth `i + 1` is the
   * last child of its own parent node. The depth of the root node is 0, and `lastChildren` for the root node should be
   * empty.
   *
   * Note that this traversal (numbering) order must be the same as [[getNodeNumbered]].
   */
  private def generateTreeString(
      depth: Int,
      lastChildren: Seq[Boolean],
      append: String => Unit,
      prefix: String = "",
      maxFields: Int): Unit = {
    if (depth > 0) {
      lastChildren.init.foreach { isLast =>
        append(if (isLast) "   " else ":  ")
      }
      append(if (lastChildren.last) "+- " else ":- ")
    }

    val str = simpleString(maxFields)
    append(prefix)
    append(str)
    append("\n")

    if (innerChildren.nonEmpty) {
      innerChildren.init.foreach(
        _.generateTreeString(depth + 2, lastChildren :+ children.isEmpty :+ false, append, maxFields = maxFields))
      innerChildren.last.generateTreeString(
        depth + 2,
        lastChildren :+ children.isEmpty :+ true,
        append,
        maxFields = maxFields)
    }

    if (children.nonEmpty) {
      children.init.foreach(_.generateTreeString(depth + 1, lastChildren :+ false, append, prefix, maxFields))
      children.last.generateTreeString(depth + 1, lastChildren :+ true, append, prefix, maxFields)
    }
  }
}
