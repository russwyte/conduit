# Conduit
A unidirectional state management library for Scala 3 utilizing ZIO

[![Maven Central](https://img.shields.io/maven-central/v/io.github.russwyte/conduit_3.svg)](https://mvnrepository.com/artifact/io.github.russwyte/conduit_3)

## Overview

Conduit is a functional, type-safe state management library built on ZIO that provides unidirectional data flow for Scala applications. It's particularly well-suited for building user interfaces, reactive applications, and any system where you need predictable state management with strong error handling.

## Key Features

### 🔄 Unidirectional Data Flow
- **Actions** trigger state changes
- **Handlers** process actions and update state
- **Listeners** react to state changes
- Predictable, debuggable state transitions

### 🎯 Strongly Typed Error Handling
- Type-safe error propagation with `IO[E, A]`
- Custom error types for different domains
- Composable error handling strategies
- No runtime surprises

### 🔍 Lens-Based State Access
- Precise targeting of nested state with optics
- Automatic lens generation with `derives Optics`
- Efficient updates without boilerplate
- Type-safe field access

### ⚡ ZIO Integration
- Built on ZIO's effect system
- Concurrent and parallel execution
- Resource-safe operations
- Fiber-based concurrency

### 🎛️ Reactive Listeners
- Subscribe to specific state changes
- Efficient change detection (only triggers on actual changes)
- Thread-safe concurrent notifications
- Functional state management with `Ref`

### ⚡ Optional Performance Optimization
- **FastEq typeclass** for optimized equality checks
- **Completely optional** - works great with standard equality
- Multiple optimization strategies (reference, version, hash, custom)
- **Zero setup required** - add optimizations only where needed

## Why Conduit for UI Development?

### Predictable State Management
Traditional UI development often suffers from unpredictable state mutations. Conduit ensures:
- All state changes go through actions
- State is immutable
- Changes are traceable and debuggable

### Efficient Updates
- Only components listening to changed state re-render
- Lens-based subscriptions target specific data
- No unnecessary UI updates

### Error Resilience
- UI errors don't crash the entire application
- Typed errors help with user feedback
- Graceful degradation strategies

### Scalable Architecture
- Modular action handlers
- Composable state management
- Easy to test and reason about

## Installation

```scala
libraryDependencies += "io.github.russwyte" %% "conduit" % "x.y.z"
```

## Quick Start

### 1. Define Your Model

```scala
case class AppState(
  counter: Int,
  user: User,
  todos: List[Todo]
) derives Optics

case class User(name: String, email: String) derives Optics
case class Todo(id: String, text: String, completed: Boolean) derives Optics
```

### 2. Create Actions

```scala
enum AppAction extends conduit.AppAction:
  case Increment
  case Decrement
  case UpdateUser(name: String, email: String)
  case AddTodo(text: String)
  case ToggleTodo(id: String)
```

### 3. Build Action Handlers

```scala
import conduit.*

val appState = Optics[AppState]

// Counter handler
val counterHandler = handle(appState(_.counter)):
  case AppAction.Increment => update(_ + 1)
  case AppAction.Decrement => update(_ - 1)

// User handler  
val userHandler = handle(appState(_.user)):
  case AppAction.UpdateUser(name, email) => 
    updated(User(name, email))

// Combined handler
val mainHandler = counterHandler >> userHandler
```

### 4. Create and Use Conduit

```scala
for {
  // Create conduit with initial state
  conduit <- Conduit(AppState(0, User("", ""), List.empty))(mainHandler)
  
  // Subscribe to state changes
  counterListener <- conduit.subscribe(_.counter) { count =>
    Console.printLine(s"Counter: $count")
  }
  
  userListener <- conduit.subscribe(_.user.name) { name =>
    Console.printLine(s"User name: $name")  
  }
  
  // Dispatch actions
  _ <- conduit(
    AppAction.Increment,
    AppAction.Increment, 
    AppAction.UpdateUser("Alice", "alice@example.com"),
    AppAction.Decrement
  )
  
  // Run the conduit
  _ <- conduit.run()
} yield ()
```

## Performance Optimization with FastEq

Conduit includes an **optional** `FastEq` typeclass for optimizing equality checks in performance-critical applications. This is particularly useful when working with large data structures or frequent state updates.

### The Problem

Reactive state management involves frequent equality checks to determine if listeners should be notified. Standard Scala equality (`==`) performs deep structural comparison, which can become a bottleneck with:

- Large collections (List, Vector, Map with thousands of elements)  
- Deeply nested case classes
- Complex data structures with expensive field comparisons
- High-frequency state updates

### The Solution: Optional FastEq

**FastEq is completely optional!** Conduit automatically falls back to standard equality when no FastEq instance is provided, so you can use the library without any performance considerations initially.

```scala
// This works perfectly without any FastEq setup
case class AppState(users: List[User], posts: List[Post]) derives Optics

val conduit = Conduit(AppState(Nil, Nil))(handler)
conduit.subscribe(_.users) { users => 
  // Uses standard equality automatically
  updateUI(users)
}
```

### Adding Performance Optimization

When you need better performance, provide FastEq instances for specific types:

```scala
import conduit.FastEq

// Reference equality for immutable objects (fastest)
given FastEq[User] = FastEq.reference[User]

// Version-based equality for objects with version fields
case class Document(id: String, content: String, version: Long)
given FastEq[Document] = FastEq.version(_.version)

// Hash-based equality for expensive comparisons
given FastEq[LargeModel] = FastEq.hash[LargeModel]

// Custom logic for domain-specific optimizations
given FastEq[BlogPost] = FastEq.create { (a, b) =>
  a.id == b.id && a.updatedAt == b.updatedAt  // Only check key fields
}
```

### Optimization Strategies

**Reference Equality**: Perfect for immutable objects where object identity indicates equality:
```scala
given FastEq[ImmutableModel] = FastEq.reference[ImmutableModel]
```

**Version-Based**: Ideal for objects with version/timestamp fields:
```scala
given FastEq[VersionedModel] = FastEq.version(_.lastModified)
```

**Hash-Based**: Useful when hash computation is faster than deep comparison:
```scala
given FastEq[ComplexModel] = FastEq.hash[ComplexModel]
```

**Dirty Flag**: For objects that track their modification state:
```scala
given FastEq[TrackingModel] = FastEq.dirty(_.isDirty, _.markClean())
```

**Custom Logic**: Domain-specific optimization strategies:
```scala
given FastEq[User] = FastEq.create { (a, b) =>
  // Fast path: check ID first (most discriminating)
  a.id == b.id && a.profileVersion == b.profileVersion
}
```

The beauty of FastEq is that it's purely additive - you can start with zero FastEq instances and add them only where performance bottlenecks appear.

### Dispatch-Level Optimization

In addition to listener-level FastEq optimization, Conduit also performs **dispatch-level optimization** automatically:

```scala
// Conduit checks if the entire model changed before notifying any listeners
val modelChanged = !FastEq.get[M].eqv(currentModel, result.newModel)
if modelChanged then
  // Only notify listeners if the model actually changed
  ZIO.foreachDiscard(listeners)(_.notify(newModel))
else ZIO.unit
```

**Benefits:**
- **Skip All Listeners**: If the entire model is unchanged, no listeners are called at all
- **Avoid Lens Evaluations**: No need to compute `lens.get(model)` for any listener
- **Batch Optimization**: One model-level check instead of per-listener checks
- **Composable**: Works together with individual listener FastEq instances

**Guarantees:**
- **Action Processing**: Actions are always processed (for side effects, logging, etc.)
- **State Updates**: State is always updated (preserves debugging and observability)
- **Semantic Preservation**: Only listener notifications are optimized

This two-level optimization approach ensures maximum performance while maintaining all the semantic guarantees of the reactive system.

## Advanced Features

### Strongly Typed Error Handling

```scala
sealed trait ValidationError extends Throwable
case class InvalidEmail(email: String) extends ValidationError
case class UserNotFound(id: String) extends ValidationError

val validatingHandler: ActionHandler[AppState, ?, ValidationError] = 
  handle[AppState, User, ValidationError](appState(_.user)):
    case UpdateUser(name, email) if isValidEmail(email) =>
      m => ZIO.succeed(ActionResult(User(name, email)))
    case UpdateUser(_, email) =>
      m => ZIO.fail(InvalidEmail(email))
```

### Composable Handlers

```scala
// Different handlers for different concerns
val authHandler: ActionHandler[AppState, ?, AuthError] = ???
val businessHandler: ActionHandler[AppState, ?, BusinessError] = ???

// Compose by widening error types
val combinedHandler: ActionHandler[AppState, ?, Throwable] =
  authHandler.widen >> businessHandler.widen
```

### Reactive UI Integration

```scala
// Perfect for reactive UI frameworks
class TodoComponent(conduit: Conduit[AppState, ?]) {
  // Subscribe to specific slice of state
  private val todosSubscription = conduit.subscribe(_.todos) { todos =>
    // Re-render only when todos change
    renderTodoList(todos)
  }
  
  def addTodo(text: String): UIO[Unit] =
    conduit(AppAction.AddTodo(text))
    
  def toggleTodo(id: String): UIO[Unit] =
    conduit(AppAction.ToggleTodo(id))
}
```

## Use Cases

### Web Applications
- React/Vue-style frontends with predictable state
- Form management and validation
- Real-time data synchronization

### Desktop Applications  
- JavaFX/Swing applications with clean architecture
- Complex UI state management
- Background task coordination

### Game Development
- Game state management
- Player actions and world updates
- UI overlays and menus

### Data Processing
- Pipeline state management
- Error handling and recovery
- Progress tracking

## Comparison with Other Solutions

| Feature | Conduit | Redux | MobX | Plain State |
|---------|---------|-------|------|-------------|
| Type Safety | ✅ Full | ⚠️ Limited | ❌ Runtime | ⚠️ Manual |
| Error Handling | ✅ Typed | ❌ Manual | ❌ Manual | ❌ Manual |
| Immutability | ✅ Enforced | ✅ Convention | ❌ Mutable | ⚠️ Manual |
| Concurrency | ✅ ZIO Fiber | ❌ Single Thread | ⚠️ Limited | ❌ Manual |
| Lens Integration | ✅ Built-in | ❌ External | ❌ No | ❌ Manual |
| Learning Curve | ⚠️ Moderate | ⚠️ Moderate | ✅ Low | ✅ Low |

## Examples

Check out the example project for complete working demonstrations:

- **[Main.scala](example/src/main/scala/example/Main.scala)** - Complete application with error handling and reactive listeners
- **[OptionalFastEqDemo.scala](example/src/main/scala/example/OptionalFastEqDemo.scala)** - Shows FastEq is completely optional (no setup required)
- **[FastEqDemo.scala](example/src/main/scala/example/FastEqDemo.scala)** - Performance optimization examples with FastEq
- **[ConduitOptimizationDemo.scala](example/src/main/scala/example/ConduitOptimizationDemo.scala)** - Demonstrates dispatch-level optimization

Features demonstrated:
- Basic state management
- Strongly typed error handling  
- Reactive listeners
- Complex nested state updates
- Optional performance optimization
- Dispatch-level model change detection

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Copyright 2025 Russ White

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this code except in compliance with the License.
You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.