# FastEq Typeclass for Performance Optimization

The `FastEq` typeclass provides an **optional** and performant way to handle equality checks in the Conduit library, addressing potential performance issues when working with large data structures in state management.

## The Problem

When using reactive state management libraries, equality checks are performed frequently to determine whether listeners should be notified of state changes. The default Scala equality (`==`) performs deep structural comparison, which can become a performance bottleneck with:

- Large collections (List, Vector, Map with thousands of elements)
- Deeply nested case classes
- Complex data structures with expensive field comparisons
- Frequent state updates in reactive applications

## The Solution: FastEq Typeclass

The `FastEq` typeclass allows library users to provide optimized equality implementations for their model types, enabling various performance optimization strategies while maintaining correctness.

**Key Feature: FastEq is completely optional!** If you don't provide a FastEq instance, the library automatically falls back to standard Scala equality (`==`). This means you can use Conduit without any FastEq knowledge and only add optimizations when needed.

### Basic Interface

```scala
trait FastEq[-A]:
  def eqv(lhs: A, rhs: A): Boolean
```

### Usage in Conduit

The `Listener` class automatically uses `FastEq` instances when available, or falls back to standard equality:

```scala
// Automatically uses FastEq.get[S] which provides fallback to standard equality
val fastEq = FastEq.get[S] 
case Some(a) if fastEq.eqv(a, newValue) => ZIO.unit
```

## No Setup Required

You can use Conduit immediately without any FastEq setup:

```scala
// This works out of the box - no FastEq instances needed!
case class MyModel(name: String, count: Int) derives Optics

val conduit = Conduit(MyModel("test", 0))(myHandler)
conduit.subscribe(_.name) { name => 
  Console.printLine(s"Name: $name")
}
```

## Optimization Strategies

### 1. Default Equality (Baseline)

For simple types or when no special optimization is needed:

```scala
case class SimpleModel(name: String, count: Int) derives Optics

object SimpleModel:
  given FastEq[SimpleModel] = FastEq.derived // Uses standard equality
```

## Optional Performance Optimization

When you want to optimize performance for specific types, you can provide FastEq instances:

```scala
// Add performance optimization for specific types
given FastEq[MyLargeModel] = FastEq.reference[MyLargeModel]

// Or with custom logic
given FastEq[ComplexModel] = FastEq.create { (a, b) =>
  a.id == b.id && a.version == b.version  // Only check key fields
}
```

## Optimization Strategies

### 1. Reference Equality First

Optimize for cases where objects might be reference-equal:

```scala
case class LargeModel(data: List[String]) derives Optics

object LargeModel:
  given FastEq[LargeModel] = FastEq.withReferenceEquality(FastEq.derived)
```

This checks `lhs eq rhs` first, then falls back to structural equality.

### 3. Version-Based Equality

For models with version or revision tracking:

```scala
case class VersionedModel(
  content: String,
  version: Long,
  largeData: Vector[ComplexObject]
) derives Optics

object VersionedModel:
  // Only compare version numbers - O(1) instead of O(n)
  given FastEq[VersionedModel] = FastEq.fromVersion(_.version)
```

### 4. Hash-Based Equality

For models where you can compute meaningful hashes:

```scala
case class HashedModel(content: String, data: List[String]) derives Optics:
  lazy val contentHash: Int = (content + data.mkString).hashCode

object HashedModel:
  given FastEq[HashedModel] = FastEq.fromHash(_.contentHash)
```

### 5. Dirty Flag Optimization

For models that track whether they've been modified:

```scala
case class DirtyFlagModel(
  data: String,
  isDirty: Boolean,
  expensiveField: Map[String, List[ComplexData]]
) derives Optics

object DirtyFlagModel:
  given FastEq[DirtyFlagModel] = FastEq.withDirtyFlag(
    _.isDirty,
    FastEq.derived // fallback when dirty
  )
```

### 6. Custom Logic

For domain-specific equality requirements:

```scala
case class TimestampedModel(data: String, timestamp: Long) derives Optics

object TimestampedModel:
  // Custom equality that ignores timestamp differences
  given FastEq[TimestampedModel] = FastEq.instance { (lhs, rhs) =>
    lhs.data == rhs.data // Only compare meaningful fields
  }
```

## Built-in Instances

FastEq provides optimized instances for common Scala types:

```scala
// Primitives
given FastEq[String] = FastEq.fromEquals
given FastEq[Int] = FastEq.fromEquals
given FastEq[Long] = FastEq.fromEquals
given FastEq[Double] = FastEq.fromEquals
given FastEq[Boolean] = FastEq.fromEquals

// Collections with size checks first
given [A](using FastEq[A]): FastEq[List[A]] = // size check + element comparison
given [A](using FastEq[A]): FastEq[Vector[A]] = // size check + element comparison
given [K, V](using FastEq[K], FastEq[V]): FastEq[Map[K, V]] = // size check + key/value comparison

// Option types
given [A](using FastEq[A]): FastEq[Option[A]] = // None/Some pattern matching
```

## Performance Considerations

### When to Use Each Strategy

| Strategy | Best For | Performance Gain | Implementation Effort |
|----------|----------|------------------|----------------------|
| Reference Equality | Immutable structures with sharing | High | Low |
| Version-based | Append-only/versioned data | Very High | Medium |
| Hash-based | Complex structures with stable hashes | High | Medium |
| Dirty flags | Mutable-style tracking | Medium | High |
| Custom logic | Domain-specific requirements | Variable | High |

### Performance Characteristics

```scala
// O(n) - Deep structural comparison
case class SlowModel(items: List[ComplexData])
given FastEq[SlowModel] = FastEq.derived

// O(1) - Version comparison only
case class FastModel(items: List[ComplexData], version: Long)
given FastEq[FastModel] = FastEq.fromVersion(_.version)
```

## Best Practices

### 1. Start Simple, Optimize When Needed

Begin with `FastEq.derived` and profile your application to identify bottlenecks:

```scala
// Start with this
given FastEq[MyModel] = FastEq.derived

// Optimize later if needed
given FastEq[MyModel] = FastEq.fromVersion(_.version)
```

### 2. Maintain Equality Laws

Ensure your FastEq implementations follow standard equality laws:

```scala
// Reflexive: eqv(a, a) == true
// Symmetric: eqv(a, b) == eqv(b, a)  
// Transitive: if eqv(a, b) && eqv(b, c) then eqv(a, c)
```

### 3. Consider Correctness vs Performance

Version-based equality is very fast but requires careful version management:

```scala
// Ensure version is updated on every meaningful change
def updateModel(model: VersionedModel, newData: String): VersionedModel =
  model.copy(
    data = newData,
    version = model.version + 1 // Critical: don't forget this!
  )
```

### 4. Use Extension Methods

The library provides convenient extension methods:

```scala
import conduit.FastEq.*

val model1 = MyModel("data")
val model2 = MyModel("data")

if model1 === model2 then // Uses FastEq instance
  println("Models are equal")
```

## Migration Guide

### Existing Code

If you have existing models without FastEq instances, add them gradually:

```scala
// Step 1: Add basic FastEq instance
case class ExistingModel(data: String) derives Optics
object ExistingModel:
  given FastEq[ExistingModel] = FastEq.derived

// Step 2: Profile and optimize if needed
object ExistingModel:
  given FastEq[ExistingModel] = FastEq.withReferenceEquality(FastEq.derived)
```

### Type Constraints

The new API requires FastEq instances for subscription targets:

```scala
// This now requires FastEq[String] (provided automatically)
conduit.subscribe(_.name) { name => /* ... */ }

// This requires FastEq[MyCustomType]
conduit.subscribe(_.customField) { field => /* ... */ }
```

## Examples

See `FastEqExamples.scala` for comprehensive examples demonstrating:
- Performance comparisons between strategies
- Custom equality implementations
- Real-world optimization patterns
- Benchmarking techniques

## Conclusion

The FastEq typeclass provides a powerful and flexible way to optimize equality checks in reactive applications. By allowing library users to implement domain-specific optimizations while maintaining type safety, it addresses performance concerns without sacrificing the benefits of functional, immutable state management.

The key insight is that different data structures and use cases benefit from different equality strategies, and a one-size-fits-all approach often leads to suboptimal performance. With FastEq, developers can choose the right optimization for their specific needs while maintaining clean, composable code.
