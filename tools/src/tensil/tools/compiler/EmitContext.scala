package tensil.tools.compiler

case class EmitContext(
    backend: Backend,
    backendStats: Option[BackendStats],
    mm: MemoryManager,
    outputNames: Seq[String]
)
