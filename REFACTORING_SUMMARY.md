# Conduit Library - Strong Error Typing Refactoring

## Overview

This refactoring introduces strong error typing to the Conduit library, moving from `UIO[A]` and `Task[A]` to `IO[E, A]` where `E` is a user-defined error type. This allows for much more precise error handling and type safety.

## Key Changes

### 1. Core Type System Updates

#### Before:
```scala
type ActionFunction[M] = M => Task[ActionResult[M]]
trait ActionHandler[M, V]
abstract class Conduit[M]
```

#### After:
```scala
type ActionFunction[M, E] = M => IO[E, ActionResult[M]]
trait ActionHandler[M, V, E]
abstract class Conduit[M, E]
```

### 2. Method Signatures

All core methods now use `IO[E, A]` instead of `UIO[A]` or `Task[A]`:

- `def run(terminate: Boolean = true): IO[E, Unit]`
- `def dispatch(action: AppAction): IO[E, AppAction]`
- `def apply(action: AppAction*): IO[Nothing, Unit]`
- `def subscribe[S](path: M => S)(listener: S => Unit): IO[Nothing, Listener[M, S]]`
- `def currentModel: IO[Nothing, M]`

### 3. Clean API Design

The API is designed for clarity and strong typing:

```scala
// Define custom error types
sealed trait ValidationError extends Throwable
case class InvalidAge(age: Int) extends ValidationError
case class InvalidName(name: String) extends ValidationError

// Create handlers with specific error types
def validatingHandler: ActionHandler[User, ?, ValidationError] = 
  handle[User, User, ValidationError](model):
    case UpdateName(name) if name.trim.nonEmpty => 
      m => ZIO.succeed(ActionResult(m.copy(name = name.trim)))
    case UpdateName(name) => 
      m => ZIO.fail(InvalidName(name))

// Create conduits with explicit error types
val conduit: Conduit[User, ValidationError] = 
  Conduit.make(user)(validatingHandler)
```

## Usage Patterns

### 1. Infallible Operations
```scala
// Operations that cannot fail use Nothing
def apply(action: AppAction*): IO[Nothing, Unit]
def currentModel: IO[Nothing, User]
```

### 2. Domain-Specific Errors
```scala
// Custom error types for business logic
sealed trait BusinessError extends Throwable
case class DuplicateEmail(email: String) extends BusinessError
case class InvalidCredentials() extends BusinessError

val handler: ActionHandler[User, ?, BusinessError] = ???
val conduit: Conduit[User, BusinessError] = Conduit.make(user)(handler)
```

### 3. Error Composition
```scala
// Combine handlers with different error types by widening to common supertype
val combinedHandler: ActionHandler[User, ?, Throwable] = 
  validationHandler.asInstanceOf[ActionHandler[User, ?, Throwable]] >>
  businessHandler.asInstanceOf[ActionHandler[User, ?, Throwable]]
```

### 4. Precise Error Handling
```scala
conduit.run().catchAll {
  case InvalidName(name) => handleInvalidName(name)
  case InvalidAge(age) => handleInvalidAge(age)
  case other => handleOtherErrors(other)
}
```

## Benefits

### 1. Type Safety
- Compile-time guarantee about what errors can occur
- No more surprise `Throwable` catches
- Clear error handling contracts

### 2. Better Error Handling
```scala
// Before: All errors are Throwable
conduit.run().catchAll(throwable => handleAnyError(throwable))

// After: Specific error types
conduit.run().catchAll {
  case InvalidName(name) => handleInvalidName(name)
  case InvalidAge(age) => handleInvalidAge(age)
}
```

### 3. Effect Tracking
- `IO[Nothing, A]` - Cannot fail
- `IO[ValidationError, A]` - Can fail with validation errors
- `IO[Throwable, A]` - Can fail with any exception

### 4. Composability
Different error types can be combined by widening to a common supertype, allowing handlers from different domains to work together.

## Advanced Usage

### Custom Error ADTs
```scala
sealed trait AppError extends Throwable
case class ValidationError(field: String, message: String) extends AppError
case class BusinessError(rule: String) extends AppError
case class SystemError(cause: Throwable) extends AppError

val handler: ActionHandler[Model, ?, AppError] = ???
val conduit: Conduit[Model, AppError] = Conduit.make(model)(handler)
```

### Error Type Widening
```scala
// Widen specific error types to more general ones when combining handlers
val wideHandler: ActionHandler[Model, ?, Throwable] = 
  specificHandler.asInstanceOf[ActionHandler[Model, ?, Throwable]]
```

## Testing

All existing tests pass, and the refactoring maintains runtime behavior while providing stronger compile-time guarantees.

## Performance

No performance impact - the refactoring only changes type signatures, not runtime behavior. The ZIO effect system efficiently handles the typed errors.

## Conclusion

This refactoring provides:
- ✅ Strong error typing with clean, explicit signatures
- ✅ Better developer experience with precise error handling
- ✅ Maintains ZIO idioms and best practices
- ✅ Clear path for domain-specific error modeling
- ✅ Composable error handling across different domains
