package io.iohk.atala.iris.sql.repository

import doobie.*
import doobie.implicits.*
import io.iohk.atala.iris.core.model
import io.iohk.atala.iris.core.repository.OperationsRepository
import io.iohk.atala.iris.sql.repository.JdbcOperationsRepository
import zio.*
import zio.interop.catz.*

// TODO: replace with actual implementation
class JdbcOperationsRepository(xa: Transactor[Task]) extends OperationsRepository[Task] {

  override def getOperation(id: model.IrisOperationId): Task[model.IrisOperation] = {
    val cxnIO = sql"""
         |SELECT foo FROM public.iris_operations
         |""".stripMargin.query[String].unique

    cxnIO
      .transact(xa)
      .map(model.IrisOperation.apply)
  }

  override def saveOperations(ops: Seq[model.IrisOperation]): Task[Unit] = ZIO.unit
}

object JdbcOperationsRepository {
  val layer: URLayer[Transactor[Task], OperationsRepository[Task]] =
    ZLayer.fromFunction(new JdbcOperationsRepository(_))
}
