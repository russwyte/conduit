package conduit.docs

import _root_.conduit.*
import ascent.*
import zio.*

/** Start a conduit and keep `run(false)` alive for the example Scope (live widgets). */
object DocsRuntime:

  def live[M <: Product: Optics: FastEq](init: M)(
      handler: ActionHandler[M, ?, Nothing]
  ): URIO[Scope, (Conduit[M, Nothing], Ctx[M])] =
    for
      c <- Conduit(init)(handler)
      _ <- c.run(false).forkScoped
    yield (c, c.ctx)
end DocsRuntime
