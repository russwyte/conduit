package example

import conduit.*
import java.io.IOException
import zio.*

/** Example showing how to implement FastEq for performance optimization. This demonstrates various strategies for
  * optimizing equality checks with large data structures.
  */
object FastEqExamples extends ZIOAppDefault:

  // Example 1: Model with large collections using standard equality
  case class LargeCollectionModel(
      id: String,
      items: List[String],
      metadata: Map[String, String],
  ) derives Optics

  object LargeCollectionModel:
    // Standard FastEq instance - will do deep equality on collections
    given FastEq[LargeCollectionModel] = FastEq.derived

  // Example 2: Model with version tracking for fast equality
  case class VersionedModel(
      id: String,
      data: String,
      version: Long,
      largeData: List[Int],
  ) derives Optics

  object VersionedModel:
    // FastEq that only compares version numbers - much faster!
    given FastEq[VersionedModel] = FastEq.fromVersion(_.version)

  // Example 3: Model with hash-based equality
  case class HashedModel(
      id: String,
      content: String,
      largePayload: Vector[String],
  ) derives Optics:
    lazy val contentHash: Int = (id + content + largePayload.mkString).hashCode

  object HashedModel:
    // FastEq that compares hashes first, then falls back to full equality
    given FastEq[HashedModel] = FastEq.instance { (lhs, rhs) =>
      // Quick hash comparison first
      if lhs.contentHash != rhs.contentHash then false
      // If hashes match, do full comparison (hash collisions are rare)
      else lhs == rhs
    }

  // Example 4: Model with dirty flag optimization
  case class DirtyFlagModel(
      id: String,
      data: String,
      isDirty: Boolean,
      expensiveComputation: Map[String, List[Int]],
  ) derives Optics

  object DirtyFlagModel:
    // FastEq that uses dirty flag for optimization
    given FastEq[DirtyFlagModel] = FastEq.withDirtyFlag(
      _.isDirty,
      FastEq.derived, // fallback to standard equality
    )

  // Example 5: Model with reference equality optimization
  case class RefEqualityModel(
      id: String,
      largeImmutableData: List[String],
  ) derives Optics

  object RefEqualityModel:
    // FastEq that tries reference equality first
    given FastEq[RefEqualityModel] = FastEq.withReferenceEquality(FastEq.derived)

  // Example handlers
  enum ModelAction extends AppAction:
    case UpdateLargeCollection(newItems: List[String])
    case IncrementVersion
    case UpdateHashed(newContent: String)
    case MarkDirty
    case UpdateRef(newData: List[String])
    case UpdateCustom(data: String, timestamp: Long)

  def performanceDemo: ZIO[Any, IOException, Unit] =
    for
      _ <- Console.printLine("=== FastEq Performance Demo ===")

      // Demo 1: Large collection with frequent updates
      _ <- Console.printLine("\n1. Large Collection Model (standard equality)")
      largeItems = (1 to 10000).map(i => s"item$i").toList
      largeModel = LargeCollectionModel("test", largeItems, Map.empty)

      // Use ActionHandler functions pattern
      largeModelOptics = Optics[LargeCollectionModel]
      largeHandler = handle[LargeCollectionModel, List[String], IOException](largeModelOptics(_.items)):
        case ModelAction.UpdateLargeCollection(items) => updated(items)

      conduit1 <- Conduit(largeModel)(largeHandler)

      // Subscribe to changes - this will trigger equality checks
      _ <- conduit1.subscribe(_.items) { items =>
        Console.printLine(s"Collection size changed: ${items.size}")
      }

      // Update with same data - should not trigger listener due to equality
      start1 <- Clock.nanoTime
      _      <- conduit1(ModelAction.UpdateLargeCollection(largeItems))
      end1   <- Clock.nanoTime
      duration1 = (end1 - start1) / 1_000_000
      _ <- Console.printLine(s"Large collection equality check: ${duration1}ms")

      // Demo 2: Versioned model with fast equality
      _ <- Console.printLine("\n2. Versioned Model (version-based equality)")
      versionedModel = VersionedModel("test", "data", 1L, (1 to 10000).toList)

      // Use ActionHandler functions pattern
      versionedModelOptics = Optics[VersionedModel]
      versionedHandler = handle[VersionedModel, Long, IOException](versionedModelOptics(_.version)):
        case ModelAction.IncrementVersion => update(_ + 1)

      conduit2 <- Conduit(versionedModel)(versionedHandler)

      _ <- conduit2.subscribe(_.version) { version =>
        Console.printLine(s"Version changed: $version")
      }

      start2 <- Clock.nanoTime
      _      <- conduit2(ModelAction.IncrementVersion)
      end2   <- Clock.nanoTime
      duration2 = (end2 - start2) / 1_000_000
      _ <- Console.printLine(s"Version-based equality check: ${duration2}ms")

      // Demo 3: Hash-based equality
      _ <- Console.printLine("\n3. Hashed Model (hash-based equality)")
      hashedModel = HashedModel("test", "content", Vector.fill(5000)("data"))

      // Use ActionHandler functions pattern
      hashedModelOptics = Optics[HashedModel]
      hashedHandler = handle[HashedModel, String, IOException](hashedModelOptics(_.content)):
        case ModelAction.UpdateHashed(content) => updated(content)

      conduit3 <- Conduit(hashedModel)(hashedHandler)

      _ <- conduit3.subscribe(_.content) { content =>
        Console.printLine(s"Content changed: ${content.take(20)}...")
      }

      start3 <- Clock.nanoTime
      _      <- conduit3(ModelAction.UpdateHashed("content")) // Same content
      end3   <- Clock.nanoTime
      duration3 = (end3 - start3) / 1_000_000
      _ <- Console.printLine(s"Hash-based equality check: ${duration3}ms")

      _ <- Console.printLine(s"\nPerformance Summary:")
      _ <- Console.printLine(s"- Standard equality: ${duration1}ms")
      _ <- Console.printLine(s"- Version-based: ${duration2}ms")
      _ <- Console.printLine(s"- Hash-based: ${duration3}ms")
      _ <- Console.printLine("\n=== Demo Complete ===")
    yield ()

  // Model for custom equality demo
  case class CustomModel(data: String, timestamp: Long) derives Optics

  object CustomModel:
    // Custom equality that only cares about data, not timestamp
    given FastEq[CustomModel] = FastEq.instance { (lhs, rhs) =>
      lhs.data == rhs.data // Ignore timestamp differences
    }

  def customEqualityDemo: ZIO[Any, IOException, Unit] =
    for
      _ <- Console.printLine("\n=== Custom Equality Strategies ===")

      // Show how library users can define their own FastEq instances
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      conduit <- Conduit(CustomModel("test", now))(
        handle[CustomModel, IOException]:
          case ModelAction.UpdateCustom(data, timestamp) =>
            updated(CustomModel(data = data, timestamp = timestamp))
      )

      _ <- conduit.subscribe(_.data) { data =>
        Console.printLine(s"Data changed (ignoring timestamp): $data")
      }

      // This won't trigger the listener because data is the same
      now2 <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      _    <- conduit(ModelAction.UpdateCustom("test", now2 + 1000))
      _    <- Console.printLine("Updated timestamp - listener not triggered due to custom equality")

      // This will trigger the listener because data changed
      now3 <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      _    <- conduit(ModelAction.UpdateCustom("updated", now3))
    yield ()

  val run =
    for
      _ <- performanceDemo
      _ <- customEqualityDemo
    yield ()
end FastEqExamples
