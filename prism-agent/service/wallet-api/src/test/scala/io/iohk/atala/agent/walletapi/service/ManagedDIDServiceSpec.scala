package io.iohk.atala.agent.walletapi.service

import io.iohk.atala.agent.walletapi.crypto.{Apollo, ApolloSpecHelper}
import io.iohk.atala.agent.walletapi.model.*
import io.iohk.atala.agent.walletapi.model.error.{
  CreateManagedDIDError,
  DIDSecretStorageError,
  PublishManagedDIDError,
  UpdateManagedDIDError
}
import io.iohk.atala.agent.walletapi.sql.*
import io.iohk.atala.agent.walletapi.storage.*
import io.iohk.atala.agent.walletapi.storage.JdbcEntityRepositorySpec.pgContainerLayer
import io.iohk.atala.agent.walletapi.vault.{VaultDIDSecretStorage, VaultWalletSecretStorage}
import io.iohk.atala.castor.core.model.did.*
import io.iohk.atala.castor.core.model.error
import io.iohk.atala.castor.core.service.DIDService
import io.iohk.atala.castor.core.util.DIDOperationValidator
import io.iohk.atala.shared.models.WalletAccessContext
import io.iohk.atala.shared.test.containers.PostgresTestContainerSupport
import io.iohk.atala.test.container.{DBTestUtils, VaultTestContainerSupport}
import zio.*
import zio.test.*
import zio.test.Assertion.*

import scala.collection.immutable.ArraySeq

object ManagedDIDServiceSpec
    extends ZIOSpecDefault,
      PostgresTestContainerSupport,
      StorageSpecHelper,
      ApolloSpecHelper,
      VaultTestContainerSupport {

  private trait TestDIDService extends DIDService {
    def getPublishedOperations: UIO[Seq[SignedPrismDIDOperation]]
    def setOperationStatus(status: ScheduledDIDOperationStatus): UIO[Unit]
    def setResolutionResult(result: Option[(DIDMetadata, DIDData)]): UIO[Unit]
  }

  private def testDIDServiceLayer = ZLayer.fromZIO {
    for {
      operationStore <- Ref.make(Seq.empty[SignedPrismDIDOperation])
      statusStore <- Ref.make[ScheduledDIDOperationStatus](ScheduledDIDOperationStatus.Pending)
      resolutionStore <- Ref.make[Option[(DIDMetadata, DIDData)]](None)
    } yield new TestDIDService {
      override def scheduleOperation(
          signOperation: SignedPrismDIDOperation
      ): IO[error.DIDOperationError, ScheduleDIDOperationOutcome] = {
        operationStore
          .update(_.appended(signOperation))
          .as(ScheduleDIDOperationOutcome(signOperation.operation.did, signOperation.operation, ArraySeq.empty))
      }

      override def resolveDID(did: PrismDID): IO[error.DIDResolutionError, Option[(DIDMetadata, DIDData)]] =
        resolutionStore.get

      override def getScheduledDIDOperationDetail(
          operationId: Array[Byte]
      ): IO[error.DIDOperationError, Option[ScheduledDIDOperationDetail]] =
        statusStore.get.map(ScheduledDIDOperationDetail(_)).asSome

      override def getPublishedOperations: UIO[Seq[SignedPrismDIDOperation]] = operationStore.get

      override def setOperationStatus(status: ScheduledDIDOperationStatus): UIO[Unit] = statusStore.set(status)

      override def setResolutionResult(result: Option[(DIDMetadata, DIDData)]): UIO[Unit] = resolutionStore.set(result)
    }
  }

  private def jdbcSecretStorageLayer =
    ZLayer.make[DIDSecretStorage & WalletSecretStorage](
      JdbcDIDSecretStorage.layer,
      JdbcWalletSecretStorage.layer,
      contextAwareTransactorLayer
    )

  private def vaultSecretStorageLayer =
    ZLayer.make[DIDSecretStorage & WalletSecretStorage](
      VaultDIDSecretStorage.layer,
      VaultWalletSecretStorage.layer,
      vaultKvClientLayer
    )

  private def serviceLayer =
    ZLayer
      .makeSome[
        DIDSecretStorage & WalletSecretStorage,
        WalletManagementService & ManagedDIDService & TestDIDService
      ](
        ManagedDIDServiceImpl.layer,
        WalletManagementServiceImpl.layer,
        DIDOperationValidator.layer(),
        JdbcDIDNonSecretStorage.layer,
        JdbcWalletNonSecretStorage.layer,
        systemTransactorLayer,
        contextAwareTransactorLayer,
        testDIDServiceLayer,
        apolloLayer
      )

  private def generateDIDTemplate(
      publicKeys: Seq[DIDPublicKeyTemplate] = Nil,
      services: Seq[Service] = Nil,
      context: Seq[String] = Nil
  ): ManagedDIDTemplate = ManagedDIDTemplate(publicKeys, services, context)

  private def resolutionResult(
      deactivated: Boolean = false,
      lastOperationHash: ArraySeq[Byte] = ArraySeq.fill(32)(0)
  ): (DIDMetadata, DIDData) = {
    val metadata = DIDMetadata(
      lastOperationHash = lastOperationHash,
      canonicalId = None,
      deactivated = deactivated,
      created = None,
      updated = None
    )
    val didData = DIDData(
      id = PrismDID.buildCanonicalFromSuffix("0" * 64).toOption.get,
      publicKeys = Nil,
      internalKeys = Nil,
      services = Nil,
      context = Seq.empty,
    )
    metadata -> didData
  }

  private val initPublishedDID =
    for {
      svc <- ZIO.service[ManagedDIDService]
      testDIDSvc <- ZIO.service[TestDIDService]
      did <- svc.createAndStoreDID(generateDIDTemplate()).map(_.asCanonical)
      _ <- svc.publishStoredDID(did)
      _ <- testDIDSvc.setOperationStatus(ScheduledDIDOperationStatus.Confirmed)
      _ <- svc.syncManagedDIDState
    } yield did

  override def spec = {
    def testSuite(name: String) =
      suite(name)(
        publishStoredDIDSpec.globalWallet,
        createAndStoreDIDSpec.globalWallet,
        createAndStorePeerDIDSpec.globalWallet,
        updateManagedDIDSpec.globalWallet,
        deactivateManagedDIDSpec.globalWallet,
        multitenantSpec
      )
        @@ TestAspect.before(DBTestUtils.runMigrationAgentDB)
        @@ TestAspect.sequential

    val suite1 = testSuite("jdbc as secret storage")
      .provide(
        serviceLayer,
        pgContainerLayer,
        jdbcSecretStorageLayer,
        contextAwareTransactorLayer >+> systemTransactorLayer >>> JdbcDIDNonSecretStorage.layer
      )
      .provide(Runtime.removeDefaultLoggers)

    val suite2 = testSuite("vault as secret storage")
      .provide(
        serviceLayer,
        pgContainerLayer,
        vaultSecretStorageLayer,
        contextAwareTransactorLayer >+> systemTransactorLayer >>> JdbcDIDNonSecretStorage.layer
      )
      .provide(Runtime.removeDefaultLoggers)

    suite("ManagedDIDService")(suite1, suite2)
  }

  private val publishStoredDIDSpec =
    suite("publishStoredDID")(
      test("publish stored DID if exists") {
        val template = generateDIDTemplate()
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- svc.createAndStoreDID(template).map(_.asCanonical)
          createOp <- svc.nonSecretStorage.getManagedDIDState(did).collect(()) {
            case Some(ManagedDIDState(op, _, PublicationState.Created())) => op
          }
          opsBefore <- testDIDSvc.getPublishedOperations
          _ <- svc.publishStoredDID(did)
          opsAfter <- testDIDSvc.getPublishedOperations
        } yield assert(opsBefore)(isEmpty) &&
          assert(opsAfter.map(_.operation))(hasSameElements(Seq(createOp)))
      },
      test("fail when publish non-existing DID") {
        val did = PrismDID
          .buildLongFormFromOperation(
            PrismDIDOperation.Create(
              Nil,
              Nil,
              Nil,
            ),
          )
          .asCanonical
        val result = ZIO.serviceWithZIO[ManagedDIDService](_.publishStoredDID(did))
        assertZIO(result.exit)(fails(isSubtype[PublishManagedDIDError.DIDNotFound](anything)))
      },
      test("set status to publication pending after publishing") {
        val template = generateDIDTemplate()
        for {
          svc <- ZIO.service[ManagedDIDService]
          did <- svc.createAndStoreDID(template).map(_.asCanonical)
          stateBefore <- svc.nonSecretStorage.getManagedDIDState(did).map(_.map(_.publicationState))
          _ <- svc.publishStoredDID(did)
          stateAfter <- svc.nonSecretStorage.getManagedDIDState(did).map(_.map(_.publicationState))
        } yield assert(stateBefore)(isSome(isSubtype[PublicationState.Created](anything)))
          && assert(stateAfter)(isSome(isSubtype[PublicationState.PublicationPending](anything)))
      },
      test("do not re-publish when publishing already published DID") {
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID // 1st publish
          _ <- svc.publishStoredDID(did) // 2nd publish
          opsAfter <- testDIDSvc.getPublishedOperations
        } yield assert(opsAfter)(hasSize(equalTo(1)))
      }
    )

  private val createAndStoreDIDSpec = suite("createAndStoreDID")(
    test("create and store DID list in DIDNonSecretStorage") {
      val template = generateDIDTemplate()
      for {
        svc <- ZIO.service[ManagedDIDService]
        didsBefore <- svc.nonSecretStorage.listManagedDID(None, None).map(_._1)
        did <- svc.createAndStoreDID(template).map(_.asCanonical)
        didsAfter <- svc.nonSecretStorage.listManagedDID(None, None).map(_._1)
      } yield assert(didsBefore)(isEmpty) &&
        assert(didsAfter.map(_._1))(hasSameElements(Seq(did)))
    },
    test("create and store DID secret in DIDSecretStorage") {
      val template = generateDIDTemplate(
        publicKeys = Seq(
          DIDPublicKeyTemplate("key1", VerificationRelationship.Authentication),
          DIDPublicKeyTemplate("key2", VerificationRelationship.KeyAgreement)
        )
      )
      for {
        svc <- ZIO.service[ManagedDIDService]
        did <- svc.createAndStoreDID(template).map(_.asCanonical)
        keyPaths <- svc.nonSecretStorage.listHdKeyPath(did)
      } yield assert(keyPaths.map(_._1))(hasSameElements(Seq("key1", "key2", ManagedDIDService.DEFAULT_MASTER_KEY_ID)))
    },
    test("created DID have corresponding public keys in CreateOperation") {
      val template = generateDIDTemplate(
        publicKeys = Seq(
          DIDPublicKeyTemplate("key1", VerificationRelationship.Authentication),
          DIDPublicKeyTemplate("key2", VerificationRelationship.KeyAgreement),
          DIDPublicKeyTemplate("key3", VerificationRelationship.AssertionMethod)
        )
      )
      for {
        svc <- ZIO.service[ManagedDIDService]
        did <- svc.createAndStoreDID(template).map(_.asCanonical)
        state <- svc.nonSecretStorage.getManagedDIDState(did)
        createOperation <- ZIO.fromOption(state.collect {
          case ManagedDIDState(operation, _, PublicationState.Created()) => operation
        })
        publicKeys = createOperation.publicKeys.collect { case pk: PublicKey => pk }
      } yield assert(publicKeys.map(i => i.id -> i.purpose))(
        hasSameElements(
          Seq(
            "key1" -> VerificationRelationship.Authentication,
            "key2" -> VerificationRelationship.KeyAgreement,
            "key3" -> VerificationRelationship.AssertionMethod
          )
        )
      )
    },
    test("created DID contain at least 1 master key in CreateOperation") {
      for {
        svc <- ZIO.service[ManagedDIDService]
        did <- svc.createAndStoreDID(generateDIDTemplate()).map(_.asCanonical)
        state <- svc.nonSecretStorage.getManagedDIDState(did)
        createOperation <- ZIO.fromOption(state.collect {
          case ManagedDIDState(operation, _, PublicationState.Created()) => operation
        })
        internalKeys = createOperation.publicKeys.collect { case pk: InternalPublicKey => pk }
      } yield assert(internalKeys.map(_.purpose))(contains(InternalKeyPurpose.Master))
    },
    test("validate DID before persisting it in storage") {
      // this template will fail during validation for reserved key id
      val template = generateDIDTemplate(
        publicKeys = Seq(
          DIDPublicKeyTemplate("master0", VerificationRelationship.Authentication)
        )
      )
      val result = ZIO.serviceWithZIO[ManagedDIDService](_.createAndStoreDID(template))
      assertZIO(result.exit)(fails(isSubtype[CreateManagedDIDError.InvalidArgument](anything)))
    },
    test("concurrent DID creation successfully create DID using different did-index") {
      for {
        svc <- ZIO.service[ManagedDIDService]
        dids <- ZIO
          .foreachPar(1 to 50)(_ => svc.createAndStoreDID(generateDIDTemplate()).map(_.asCanonical))
          .withParallelism(8)
          .map(_.toList)
        states <- ZIO
          .foreach(dids)(did => svc.nonSecretStorage.getManagedDIDState(did))
          .map(_.toList.flatten)
      } yield assert(dids)(hasSize(equalTo(50))) &&
        assert(states.map(_.didIndex))(hasSameElementsDistinct(0 until 50))
    }
  )

  private val createAndStorePeerDIDSpec = suite("createAndStorePeerDID")(
    test("can get PeerDIDRecord from any wallet") {
      for {
        walletSvc <- ZIO.service[WalletManagementService]
        walletId1 <- walletSvc.createWallet(Wallet("wallet-1")).map(_.id)
        walletId2 <- walletSvc.createWallet(Wallet("wallet-2")).map(_.id)
        ctx1 = ZLayer.succeed(WalletAccessContext(walletId1))
        ctx2 = ZLayer.succeed(WalletAccessContext(walletId2))
        svc <- ZIO.service[ManagedDIDService]
        storage <- ZIO.service[DIDNonSecretStorage]
        peerDid1 <- svc.createAndStorePeerDID("http://example.com").provide(ctx1)
        peerDid2 <- svc.createAndStorePeerDID("http://example.com").provide(ctx2)
        record1 <- storage.getPeerDIDRecord(peerDid1.did)
        record2 <- storage.getPeerDIDRecord(peerDid2.did)
      } yield {
        assertTrue(record1.isDefined) &&
        assertTrue(record1.get.did == peerDid1.did) &&
        assertTrue(record1.get.walletId == walletId1) &&
        assertTrue(record2.isDefined) &&
        assertTrue(record2.get.did == peerDid2.did) &&
        assertTrue(record2.get.walletId == walletId2)
      }
    }
  )

  private val updateManagedDIDSpec =
    suite("updateManagedDID")(
      test("update stored and published DID") {
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID
          _ <- testDIDSvc.setResolutionResult(Some(resolutionResult()))
          _ <- svc.updateManagedDID(did, Seq(UpdateManagedDIDAction.RemoveKey("key-1")))
          operations <- testDIDSvc.getPublishedOperations
        } yield assert(operations.map(_.operation))(exists(isSubtype[PrismDIDOperation.Update](anything)))
      },
      test("fail on updating non-existing DID") {
        val did = PrismDID.buildCanonicalFromSuffix("0" * 64).toOption.get
        val effect = for {
          svc <- ZIO.service[ManagedDIDService]
          _ <- svc.updateManagedDID(did, Seq(UpdateManagedDIDAction.RemoveKey("key-1")))
        } yield ()
        assertZIO(effect.exit)(fails(isSubtype[UpdateManagedDIDError.DIDNotFound](anything)))
      },
      test("fail on updating unpublished DID") {
        val template = generateDIDTemplate()
        val effect = for {
          svc <- ZIO.service[ManagedDIDService]
          did <- svc.createAndStoreDID(template).map(_.asCanonical)
          _ <- svc.updateManagedDID(did, Seq(UpdateManagedDIDAction.RemoveKey("key-1")))
        } yield ()
        assertZIO(effect.exit)(fails(isSubtype[UpdateManagedDIDError.DIDNotPublished](anything)))
      },
      test("fail on deactivated DID") {
        val effect = for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID
          // set did as deactivated
          _ <- testDIDSvc.setResolutionResult(Some(resolutionResult(deactivated = true)))
          _ <- svc.updateManagedDID(did, Seq(UpdateManagedDIDAction.RemoveKey("key-1")))
        } yield ()
        assertZIO(effect.exit)(fails(isSubtype[UpdateManagedDIDError.DIDAlreadyDeactivated](anything)))
      },
      test("validate constructed operation before submitting an operation") {
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID
          _ <- testDIDSvc.setResolutionResult(Some(resolutionResult()))
          // catch expected validation error and assert that operation was not submitted
          _ <- svc.updateManagedDID(did, Nil).catchSome { case _: UpdateManagedDIDError.InvalidOperation => ZIO.unit }
          operations <- testDIDSvc.getPublishedOperations
        } yield assert(operations)(hasSize(equalTo(1)))
      },
      test("store private keys in update operation") {
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID
          _ <- testDIDSvc.setResolutionResult(Some(resolutionResult()))
          actions = Seq("key-1", "key-2").map(id =>
            UpdateManagedDIDAction.AddKey(DIDPublicKeyTemplate(id, VerificationRelationship.Authentication))
          )
          _ <- svc.updateManagedDID(did, actions)
          keyPaths <- svc.nonSecretStorage.listHdKeyPath(did)
        } yield assert(keyPaths.map(_._1))(
          hasSameElements(Seq(ManagedDIDService.DEFAULT_MASTER_KEY_ID, "key-1", "key-2"))
        )
      },
      test("store private keys with the same key-id across multiple update operation") {
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID
          _ <- testDIDSvc.setResolutionResult(Some(resolutionResult()))
          actions = Seq("key-1", "key-2").map(id =>
            UpdateManagedDIDAction.AddKey(DIDPublicKeyTemplate(id, VerificationRelationship.Authentication))
          )
          _ <- svc.updateManagedDID(did, actions) // 1st update
          _ <- svc.updateManagedDID(did, actions.take(1)) // 2nd update: key-1 is added twice
          keyPaths <- svc.nonSecretStorage.listHdKeyPath(did)
        } yield assert(keyPaths.map(_._1))(
          hasSameElements(Seq(ManagedDIDService.DEFAULT_MASTER_KEY_ID, "key-1", "key-1", "key-2"))
        )
      },
      test("store did lineage for each update operation") {
        for {
          svc <- ZIO.service[ManagedDIDService]
          testDIDSvc <- ZIO.service[TestDIDService]
          did <- initPublishedDID
          _ <- testDIDSvc.setResolutionResult(Some(resolutionResult()))
          _ <- ZIO.foreach(1 to 5) { _ =>
            val actions = Seq(UpdateManagedDIDAction.RemoveKey("key-1"))
            svc.updateManagedDID(did, actions)
          }
          _ <- ZIO.foreach(1 to 5) { _ =>
            val actions =
              Seq(UpdateManagedDIDAction.AddKey(DIDPublicKeyTemplate("key-1", VerificationRelationship.Authentication)))
            svc.updateManagedDID(did, actions)
          }
          lineage <- svc.nonSecretStorage.listUpdateLineage(None, None)
        } yield {
          // There are a total of 10 updates: 5 add-key updates & 5 remove-key updates.
          // There should be 10 unique operationId (randomness in signature) and
          // 6 unique operationHash since remove-key update all have the same content
          // and add-key all have different content (randomness in key generation).
          assert(lineage)(hasSize(equalTo(10)))
          && assert(lineage.map(_.operationId).toSet)(hasSize(equalTo(10)))
          && assert(lineage.map(_.operationHash).toSet)(hasSize(equalTo(6)))
        }
      }
    )

  private val deactivateManagedDIDSpec = suite("deactivateManagedDID")(
    test("deactivate published DID") {
      for {
        svc <- ZIO.service[ManagedDIDService]
        testDIDSvc <- ZIO.service[TestDIDService]
        did <- initPublishedDID
        _ <- testDIDSvc.setResolutionResult(Some(resolutionResult()))
        _ <- svc.deactivateManagedDID(did)
        operations <- testDIDSvc.getPublishedOperations
      } yield assert(operations.map(_.operation))(exists(isSubtype[PrismDIDOperation.Deactivate](anything)))
    },
    test("fail on deactivating non-existing DID") {
      val did = PrismDID.buildCanonicalFromSuffix("0" * 64).toOption.get
      val effect = for {
        svc <- ZIO.service[ManagedDIDService]
        _ <- svc.deactivateManagedDID(did)
      } yield ()
      assertZIO(effect.exit)(fails(isSubtype[UpdateManagedDIDError.DIDNotFound](anything)))
    },
    test("fail on deactivating unpublished DID") {
      val template = generateDIDTemplate()
      val effect = for {
        svc <- ZIO.service[ManagedDIDService]
        did <- svc.createAndStoreDID(template).map(_.asCanonical)
        _ <- svc.deactivateManagedDID(did)
      } yield ()
      assertZIO(effect.exit)(fails(isSubtype[UpdateManagedDIDError.DIDNotPublished](anything)))
    },
    test("fail on deactivating deactivated DID") {
      val effect = for {
        svc <- ZIO.service[ManagedDIDService]
        testDIDSvc <- ZIO.service[TestDIDService]
        did <- initPublishedDID
        _ <- testDIDSvc.setResolutionResult(Some(resolutionResult(deactivated = true)))
        _ <- svc.deactivateManagedDID(did)
      } yield ()
      assertZIO(effect.exit)(fails(isSubtype[UpdateManagedDIDError.DIDAlreadyDeactivated](anything)))
    }
  )

  private val multitenantSpec = suite("multi-tenant managed DID")(
    test("do not see Prism DID outside of the wallet") {
      val template = generateDIDTemplate()
      for {
        walletSvc <- ZIO.service[WalletManagementService]
        walletId1 <- walletSvc.createWallet(Wallet("wallet-1")).map(_.id)
        walletId2 <- walletSvc.createWallet(Wallet("wallet-2")).map(_.id)
        ctx1 = ZLayer.succeed(WalletAccessContext(walletId1))
        ctx2 = ZLayer.succeed(WalletAccessContext(walletId2))
        svc <- ZIO.service[ManagedDIDService]
        dids1 <- ZIO.foreach(1 to 3)(_ => svc.createAndStoreDID(template).map(_.asCanonical)).provide(ctx1)
        dids2 <- ZIO.foreach(1 to 3)(_ => svc.createAndStoreDID(template).map(_.asCanonical)).provide(ctx2)
        ownWalletDids1 <- svc.listManagedDIDPage(0, 1000).map(_._1.map(_.did)).provide(ctx1)
        ownWalletDids2 <- svc.listManagedDIDPage(0, 1000).map(_._1.map(_.did)).provide(ctx2)
        crossWalletDids1 <- ZIO.foreach(dids1)(did => svc.getManagedDIDState(did)).provide(ctx2)
        crossWalletDids2 <- ZIO.foreach(dids2)(did => svc.getManagedDIDState(did)).provide(ctx1)
      } yield assert(dids1)(hasSameElements(ownWalletDids1)) &&
        assert(dids2)(hasSameElements(ownWalletDids2)) &&
        assert(crossWalletDids1)(forall(isNone)) &&
        assert(crossWalletDids2)(forall(isNone))
    },
    test("do not see Peer DID outside of the wallet") {
      for {
        walletSvc <- ZIO.service[WalletManagementService]
        walletId1 <- walletSvc.createWallet(Wallet("wallet-1")).map(_.id)
        walletId2 <- walletSvc.createWallet(Wallet("wallet-2")).map(_.id)
        ctx1 = ZLayer.succeed(WalletAccessContext(walletId1))
        ctx2 = ZLayer.succeed(WalletAccessContext(walletId2))
        svc <- ZIO.service[ManagedDIDService]
        dids1 <- ZIO.foreach(1 to 3)(_ => svc.createAndStorePeerDID("http://example.com")).provide(ctx1)
        dids2 <- ZIO.foreach(1 to 3)(_ => svc.createAndStorePeerDID("http://example.com")).provide(ctx2)
        ownWalletDids1 <- ZIO.foreach(dids1)(d => svc.getPeerDID(d.did).exit).provide(ctx1)
        ownWalletDids2 <- ZIO.foreach(dids2)(d => svc.getPeerDID(d.did).exit).provide(ctx2)
        crossWalletDids1 <- ZIO.foreach(dids1)(d => svc.getPeerDID(d.did).exit).provide(ctx2)
        crossWalletDids2 <- ZIO.foreach(dids2)(d => svc.getPeerDID(d.did).exit).provide(ctx1)
      } yield assert(ownWalletDids1)(forall(succeeds(anything))) &&
        assert(ownWalletDids2)(forall(succeeds(anything))) &&
        assert(crossWalletDids1)(forall(failsWithA[DIDSecretStorageError.KeyNotFoundError])) &&
        assert(crossWalletDids2)(forall(failsWithA[DIDSecretStorageError.KeyNotFoundError]))
    },
    test("increment DID index based on count only on its wallet") {
      val template = generateDIDTemplate()
      for {
        walletSvc <- ZIO.service[WalletManagementService]
        walletId1 <- walletSvc.createWallet(Wallet("wallet-1")).map(_.id)
        walletId2 <- walletSvc.createWallet(Wallet("wallet-2")).map(_.id)
        ctx1 = ZLayer.succeed(WalletAccessContext(walletId1))
        ctx2 = ZLayer.succeed(WalletAccessContext(walletId2))
        svc <- ZIO.service[ManagedDIDService]
        wallet1Counter1 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx1)
        wallet2Counter1 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx2)
        _ <- svc.createAndStoreDID(template).provide(ctx1)
        wallet1Counter2 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx1)
        wallet2Counter2 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx2)
        _ <- svc.createAndStoreDID(template).provide(ctx1)
        wallet1Counter3 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx1)
        wallet2Counter3 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx2)
        _ <- svc.createAndStoreDID(template).provide(ctx2)
        wallet1Counter4 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx1)
        wallet2Counter4 <- svc.nonSecretStorage.getMaxDIDIndex().provide(ctx2)
      } yield {
        // initial counter
        assert(wallet1Counter1)(isNone) &&
        assert(wallet2Counter1)(isNone) &&
        // add DID to wallet 1
        assert(wallet1Counter2)(isSome(equalTo(0))) &&
        assert(wallet2Counter2)(isNone) &&
        // add DID to wallet 1
        assert(wallet1Counter3)(isSome(equalTo(1))) &&
        assert(wallet2Counter3)(isNone) &&
        // add DID to wallet 2
        assert(wallet1Counter4)(isSome(equalTo(1))) &&
        assert(wallet2Counter4)(isSome(equalTo(0)))
      }
    }
  )

}
