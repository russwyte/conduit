package conduit

import zio.*
import zio.test.*
import conduit.FastEq.{===, !==}

object FastEqSpec extends ZIOSpecDefault:

  // Test models for different FastEq scenarios
  case class SimpleModel(value: Int) derives Optics
  case class ModelWithMetadata(value: Int, metadata: String) derives Optics
  case class VersionedModel(id: String, data: String, version: Long) derives Optics
  case class ModelWithHash(content: String, payload: Vector[String]) derives Optics:
    lazy val contentHash: Int = (content + payload.mkString).hashCode

  // Custom FastEq instances for testing
  given FastEq[ModelWithMetadata] = FastEq.instance { (a, b) =>
    a.value == b.value // Ignore metadata field
  }

  given FastEq[VersionedModel] = FastEq.fromVersion(_.version)

  given FastEq[ModelWithHash] = FastEq.instance { (lhs, rhs) =>
    if lhs.contentHash != rhs.contentHash then false
    else lhs == rhs
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FastEq")(
      suite("Basic Operations")(
        test("should provide default equality for types without custom FastEq") {
          val model1 = SimpleModel(42)
          val model2 = SimpleModel(42)
          val model3 = SimpleModel(99)

          val fastEq = FastEq.get[SimpleModel]
          assertTrue(fastEq.eqv(model1, model2)) &&  // Same values
          assertTrue(!fastEq.eqv(model1, model3)) && // Different values
          assertTrue(fastEq.eqv(model1, model1))     // Reflexive
        },
        test("should use custom FastEq instances when provided") {
          val model1 = ModelWithMetadata(42, "initial")
          val model2 = ModelWithMetadata(42, "changed") // Same value, different metadata
          val model3 = ModelWithMetadata(99, "initial") // Different value, same metadata

          val fastEq = FastEq.get[ModelWithMetadata]
          assertTrue(fastEq.eqv(model1, model2)) && // Should be equal (ignore metadata)
          assertTrue(!fastEq.eqv(model1, model3))   // Should be different (value changed)
        },
      ),
      suite("Version-Based Equality")(
        test("should compare only version numbers") {
          val model1 = VersionedModel("id1", "data1", 1L)
          val model2 = VersionedModel("id2", "data2", 1L) // Different id/data, same version
          val model3 = VersionedModel("id1", "data1", 2L) // Same id/data, different version

          val fastEq = FastEq.get[VersionedModel]
          assertTrue(fastEq.eqv(model1, model2)) && // Should be equal (same version)
          assertTrue(!fastEq.eqv(model1, model3))   // Should be different (different version)
        }
      ),
      suite("Hash-Based Equality")(
        test("should use hash for fast comparison") {
          val payload = Vector("data1", "data2", "data3")
          val model1  = ModelWithHash("content", payload)
          val model2  = ModelWithHash("content", payload)   // Same content and payload
          val model3  = ModelWithHash("different", payload) // Different content

          val fastEq = FastEq.get[ModelWithHash]
          assertTrue(fastEq.eqv(model1, model2)) && // Same hash and content
          assertTrue(!fastEq.eqv(model1, model3))   // Different hash
        }
      ),
      suite("Factory Methods")(
        test("FastEq.instance should create working instances") {
          case class TestModel(a: Int, b: String)
          val customEq = FastEq.instance[TestModel] { (lhs, rhs) =>
            lhs.a == rhs.a // Only compare 'a' field
          }

          val model1 = TestModel(1, "x")
          val model2 = TestModel(1, "y") // Same 'a', different 'b'
          val model3 = TestModel(2, "x") // Different 'a', same 'b'

          assertTrue(customEq.eqv(model1, model2)) && // Should be equal
          assertTrue(!customEq.eqv(model1, model3))   // Should be different
        },
        test("FastEq.fromEquals should delegate to standard equality") {
          case class TestModel(value: Int)
          val standardEq = FastEq.fromEquals[TestModel]

          val model1 = TestModel(42)
          val model2 = TestModel(42)
          val model3 = TestModel(99)

          assertTrue(standardEq.eqv(model1, model2)) && // Same
          assertTrue(!standardEq.eqv(model1, model3))   // Different
        },
        test("FastEq.withReferenceEquality should try reference equality first") {
          case class TestModel(value: Int)
          var fallbackCallCount = 0

          val slowEq = FastEq.instance[TestModel] { (lhs, rhs) =>
            fallbackCallCount += 1
            lhs.value == rhs.value
          }
          val refEq = FastEq.withReferenceEquality(slowEq)

          val model1 = TestModel(42)
          val model2 = model1        // Same reference
          val model3 = TestModel(42) // Different reference, same value

          for
            // Reset counter
            _ <- ZIO.succeed { fallbackCallCount = 0 }

            // Test reference equality - should not call fallback
            result1 <- ZIO.succeed(refEq.eqv(model1, model2))
            callsAfterRef = fallbackCallCount

            // Test different references - should call fallback
            result2 <- ZIO.succeed(refEq.eqv(model1, model3))
            callsAfterFallback = fallbackCallCount
          yield assertTrue(result1) &&
            assertTrue(result2) &&
            assertTrue(callsAfterRef == 0) &&   // Reference equality didn't call fallback
            assertTrue(callsAfterFallback == 1) // Different references called fallback once
        },
      ),
      suite("Collection Support")(
        test("should work with Option types") {
          given FastEq[String] = FastEq.fromEquals[String]

          val fastEq = FastEq.get[Option[String]]
          assertTrue(fastEq.eqv(Some("test"), Some("test"))) &&   // Same Some
          assertTrue(!fastEq.eqv(Some("test"), Some("other"))) && // Different Some
          assertTrue(fastEq.eqv(None, None)) &&                   // Both None
          assertTrue(!fastEq.eqv(Some("test"), None))             // Some vs None
        },
        test("should work with List types") {
          given FastEq[Int] = FastEq.fromEquals[Int]

          val fastEq = FastEq.get[List[Int]]
          assertTrue(fastEq.eqv(List(1, 2, 3), List(1, 2, 3))) &&  // Same lists
          assertTrue(!fastEq.eqv(List(1, 2, 3), List(1, 2, 4))) && // Different elements
          assertTrue(!fastEq.eqv(List(1, 2, 3), List(1, 2))) &&    // Different lengths
          assertTrue(fastEq.eqv(List.empty[Int], List.empty[Int])) // Empty lists
        },
      ),
      suite("Extension Methods")(
        test("=== and !== operators should work") {
          val model1 = ModelWithMetadata(42, "initial")
          val model2 = ModelWithMetadata(42, "changed") // Should be equal per our custom FastEq
          val model3 = ModelWithMetadata(99, "initial") // Should be different

          assertTrue(model1 === model2) &&    // Should be equal
          assertTrue(!(model1 !== model2)) && // Should not be unequal
          assertTrue(!(model1 === model3)) && // Should not be equal
          assertTrue(model1 !== model3)       // Should be unequal
        }
      ),
      suite("Laws and Properties")(
        test("should satisfy reflexivity") {
          val model  = ModelWithMetadata(42, "test")
          val fastEq = FastEq.get[ModelWithMetadata]
          assertTrue(fastEq.eqv(model, model))
        },
        test("should satisfy symmetry") {
          val model1  = ModelWithMetadata(42, "test1")
          val model2  = ModelWithMetadata(42, "test2")
          val fastEq  = FastEq.get[ModelWithMetadata]
          val result1 = fastEq.eqv(model1, model2)
          val result2 = fastEq.eqv(model2, model1)
          assertTrue(result1 == result2)
        },
        test("should satisfy transitivity") {
          val model1 = ModelWithMetadata(42, "test1")
          val model2 = ModelWithMetadata(42, "test2")
          val model3 = ModelWithMetadata(42, "test3")
          val fastEq = FastEq.get[ModelWithMetadata]
          val eq12   = fastEq.eqv(model1, model2)
          val eq23   = fastEq.eqv(model2, model3)
          val eq13   = fastEq.eqv(model1, model3)
          assertTrue(!eq12 || !eq23 || eq13) // If a==b and b==c then a==c
        },
      ),
    )
end FastEqSpec
