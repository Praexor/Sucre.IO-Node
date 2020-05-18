package com.wavesplatform.database

import com.google.common.primitives.{Ints, Shorts}
import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.block.Block
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.db.{DBCacheSettings, WithDomain}
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.lang.script.v1.ExprScript
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.estimator.ScriptEstimatorV1
import com.wavesplatform.settings.{TestFunctionalitySettings, WavesSettings, loadConfig}
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.state.utils.{BlockchainAddressTransactionsList, _}
import com.wavesplatform.state.{BlockchainUpdaterImpl, Height, TransactionId, TxNum}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.{IssueTransactionV1, SponsorFeeTransaction}
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.wavesplatform.transaction.transfer.{TransferTransaction, TransferTransactionV1}
import com.wavesplatform.transaction.{GenesisTransaction, Transaction}
import com.wavesplatform.utils.Time
import com.wavesplatform.{RequestGen, TransactionGen, WithDB}
import org.scalacheck.Gen
import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

//noinspection NameBooleanParameters
class LevelDBWriterSpec
    extends FreeSpec
    with Matchers
    with TransactionGen
    with WithDB
    with DBCacheSettings
    with RequestGen
    with ScalaCheckDrivenPropertyChecks
    with WithDomain {
  "Slice" - {
    "drops tail" in {
      LevelDBWriter.slice(Seq(10, 7, 4), 7, 10) shouldEqual Seq(10, 7)
    }
    "drops head" in {
      LevelDBWriter.slice(Seq(10, 7, 4), 4, 8) shouldEqual Seq(7, 4)
    }
    "includes Genesis" in {
      LevelDBWriter.slice(Seq(10, 7), 5, 11) shouldEqual Seq(10, 7, 1)
    }
  }
  "Merge" - {
    import TestFunctionalitySettings.Enabled
    "correctly joins height ranges" in {
      val fs     = Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.SmartAccountTrading.id -> 0))
      val writer = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, fs, dbSettings)
      writer.merge(Seq(15, 12, 3), Seq(12, 5)) shouldEqual Seq((15, 12), (12, 12), (3, 5))
      writer.merge(Seq(12, 5), Seq(15, 12, 3)) shouldEqual Seq((12, 15), (12, 12), (5, 3))
      writer.merge(Seq(8, 4), Seq(8, 4)) shouldEqual Seq((8, 8), (4, 4))
    }
  }
  "hasScript" - {
    "returns false if a script was not set" in {
      val writer = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)
      writer.hasScript(accountGen.sample.get.toAddress) shouldBe false
    }

    "returns false if a script was set and then unset" in {
      assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
      resetTest { (_, account) =>
        val writer = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)
        writer.hasScript(account) shouldBe false
      }
    }

    "returns true" - {
      "if there is a script in db" in {
        assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
        test { (_, account) =>
          val writer = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)
          writer.hasScript(account) shouldBe true
        }
      }

      "if there is a script in cache" in {
        assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
        test { (defaultWriter, account) =>
          defaultWriter.hasScript(account) shouldBe true
        }
      }
    }

    def gen(ts: Long): Gen[(KeyPair, Seq[Block])] = baseGen(ts).map {
      case (master, blocks) =>
        val nextBlock = TestBlock.create(ts + 1, blocks.last.uniqueId, Seq())
        (master, blocks :+ nextBlock)
    }

    def resetGen(ts: Long): Gen[(KeyPair, Seq[Block])] = baseGen(ts).map {
      case (master, blocks) =>
        val unsetScriptTx = SetScriptTransaction
          .selfSigned(master, None, 5000000, ts + 1)
          .explicitGet()

        val block1 = TestBlock.create(ts + 1, blocks.last.uniqueId, Seq(unsetScriptTx))
        val block2 = TestBlock.create(ts + 2, block1.uniqueId, Seq())
        (master, blocks ++ List(block1, block2))
    }

    def baseGen(ts: Long): Gen[(KeyPair, Seq[Block])] = accountGen.map { master =>
      val genesisTx = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      val setScriptTx = SetScriptTransaction
        .selfSigned(master, Some(ExprScript(Terms.TRUE).explicitGet()), 5000000, ts)
        .explicitGet()

      val block = TestBlock.create(ts, Seq(genesisTx, setScriptTx))
      (master, Seq(block))
    }

    def test(f: (LevelDBWriter, KeyPair) => Unit): Unit = baseTest(t => gen(t.correctedTime()))(f)

    def resetTest(f: (LevelDBWriter, KeyPair) => Unit): Unit = baseTest(t => resetGen(t.correctedTime()))(f)

  }

  def baseTest(gen: Time => Gen[(KeyPair, Seq[Block])])(f: (LevelDBWriter, KeyPair) => Unit): Unit = {
    val defaultWriter = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)
    val settings0     = WavesSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val settings      = settings0.copy(featuresSettings = settings0.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false))
    val bcu           = new BlockchainUpdaterImpl(defaultWriter, ignoreSpendableBalanceChanged, settings, ntpTime)
    try {
      val (account, blocks) = gen(ntpTime).sample.get

      blocks.foreach { block =>
        bcu.processBlock(block).explicitGet()
      }

      bcu.shutdown()
      f(defaultWriter, account)
    } finally {
      bcu.shutdown()
      db.close()
    }
  }

  def testWithBlocks(gen: Time => Gen[(KeyPair, Seq[Block])])(f: (LevelDBWriter, Seq[Block], KeyPair) => Unit): Unit = {
    val defaultWriter = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)
    val settings0     = WavesSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val settings      = settings0.copy(featuresSettings = settings0.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false))
    val bcu           = new BlockchainUpdaterImpl(defaultWriter, ignoreSpendableBalanceChanged, settings, ntpTime)
    try {
      val (account, blocks) = gen(ntpTime).sample.get

      blocks.foreach { block =>
        bcu.processBlock(block).explicitGet()
      }

      bcu.shutdown()
      f(defaultWriter, blocks, account)
    } finally {
      bcu.shutdown()
      db.close()
    }
  }

  def createTransfer(master: KeyPair, recipient: Address, ts: Long): TransferTransaction = {
    TransferTransactionV1
      .selfSigned(Waves, master, recipient, ENOUGH_AMT / 5, ts, Waves, 1000000, Array.emptyByteArray)
      .explicitGet()
  }

  def preconditions(ts: Long): Gen[(KeyPair, List[Block])] = {
    for {
      master    <- accountGen
      recipient <- accountGen
      genesisBlock = TestBlock
        .create(ts, Seq(GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()))
      block1 = TestBlock
        .create(
          ts + 3,
          genesisBlock.uniqueId,
          Seq(
            createTransfer(master, recipient.toAddress, ts + 1),
            createTransfer(master, recipient.toAddress, ts + 2)
          )
        )
      emptyBlock = TestBlock.create(ts + 5, block1.uniqueId, Seq())
    } yield (master, List(genesisBlock, block1, emptyBlock))
  }

  "correctly reassemble block from header and transactions" in {
    val rw        = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)
    val settings0 = WavesSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val settings  = settings0.copy(featuresSettings = settings0.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false))
    val bcu       = new BlockchainUpdaterImpl(rw, ignoreSpendableBalanceChanged, settings, ntpTime)
    try {
      val master    = KeyPair("master".getBytes())
      val recipient = KeyPair("recipient".getBytes())

      val ts = System.currentTimeMillis()

      val genesisBlock = TestBlock
        .create(ts, Seq(GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()))
      val block1 = TestBlock
        .create(
          ts + 3,
          genesisBlock.uniqueId,
          Seq(
            createTransfer(master, recipient.toAddress, ts + 1),
            createTransfer(master, recipient.toAddress, ts + 2)
          )
        )
      val block2 = TestBlock.create(ts + 5, block1.uniqueId, Seq())
      val block3 = TestBlock
        .create(
          ts + 10,
          block2.uniqueId,
          Seq(
            createTransfer(master, recipient.toAddress, ts + 6),
            createTransfer(master, recipient.toAddress, ts + 7)
          )
        )

      bcu.processBlock(genesisBlock) shouldBe 'right
      bcu.processBlock(block1) shouldBe 'right
      bcu.processBlock(block2) shouldBe 'right
      bcu.processBlock(block3) shouldBe 'right

      bcu.blockAt(1).get shouldBe genesisBlock
      bcu.blockAt(2).get shouldBe block1
      bcu.blockAt(3).get shouldBe block2
      bcu.blockAt(4).get shouldBe block3

      for (i <- 1 to db.get(Keys.height)) {
        db.get(Keys.blockHeaderAndSizeAt(Height(i))).isDefined shouldBe true
      }

      bcu.blockBytes(1).get shouldBe genesisBlock.bytes()
      bcu.blockBytes(2).get shouldBe block1.bytes()
      bcu.blockBytes(3).get shouldBe block2.bytes()
      bcu.blockBytes(4).get shouldBe block3.bytes()

    } finally {
      bcu.shutdown()
      db.close()
    }
  }

  "addressTransactions" - {

    "return txs in correct ordering without fromId" in {
      baseTest(time => preconditions(time.correctedTime())) { (writer, account) =>
        val txs = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 3, None)
          .explicitGet()

        val ordering = Ordering
          .by[(Int, Transaction), (Int, Long)]({ case (h, t) => (-h, -t.timestamp) })

        txs.length shouldBe 2

        txs.sorted(ordering) shouldBe txs
      }
    }

    "correctly applies transaction type filter" in {
      baseTest(time => preconditions(time.correctedTime())) { (writer, account) =>
        val txs = writer
          .addressTransactions(account.toAddress, Set(GenesisTransaction.typeId), 10, None)
          .explicitGet()

        txs.length shouldBe 1
      }
    }

    "return Left if fromId argument is a non-existent transaction" in {
      baseTest(time => preconditions(time.correctedTime())) { (writer, account) =>
        val nonExistentTxId = GenesisTransaction.create(account, ENOUGH_AMT, 1).explicitGet().id()

        val txs = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 3, Some(nonExistentTxId))

        txs shouldBe Left(s"Transaction $nonExistentTxId does not exist")
      }
    }

    "return txs in correct ordering starting from a given id" in {
      baseTest(time => preconditions(time.correctedTime())) { (writer, account) =>
        // using pagination
        val firstTx = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 1, None)
          .explicitGet()
          .head

        val secondTx = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 1, Some(firstTx._2.id()))
          .explicitGet()
          .head

        // without pagination
        val txs = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 2, None)
          .explicitGet()

        txs shouldBe Seq(firstTx, secondTx)
      }
    }

    "return an empty Seq when paginating from the last transaction" in {
      baseTest(time => preconditions(time.correctedTime())) { (writer, account) =>
        val txs = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 2, None)
          .explicitGet()

        val txsFromLast = writer
          .addressTransactions(account.toAddress, Set(TransferTransactionV1.typeId), 2, Some(txs.last._2.id()))
          .explicitGet()

        txs.length shouldBe 2
        txsFromLast shouldBe Seq.empty
      }
    }

    "don't parse irrelevant transactions in transferById" in {
      val writer = TestLevelDB.withFunctionalitySettings(db, ignoreSpendableBalanceChanged, TestFunctionalitySettings.Stub, dbSettings)

      forAll(randomTransactionGen) { tx =>
        val transactionId = tx.id()
        db.put(Keys.transactionHNById(TransactionId @@ transactionId).keyBytes, Ints.toByteArray(1) ++ Shorts.toByteArray(0))
        db.put(Keys.transactionBytesAt(Height @@ 1, TxNum @@ 0.toShort).keyBytes, Array[Byte](1, 2, 3, 4, 5, 6))

        writer.transferById(transactionId) shouldBe None

        db.put(Keys.transactionBytesAt(Height @@ 1, TxNum @@ 0.toShort).keyBytes, Array[Byte](TransferTransaction.typeId, 2, 3, 4, 5, 6))
        intercept[ArrayIndexOutOfBoundsException](writer.transferById(transactionId))
      }
    }
  }

  "reset invalid sponsored invokes" in {
    val preconditions = for {
      acc <- accountGen
      fee <- smallFeeGen
      ts      = System.currentTimeMillis()
      genesis = GenesisTransaction.create(acc, ENOUGH_AMT, ts).explicitGet()
      issue   = IssueTransactionV1.selfSigned(acc, "test".getBytes, "test".getBytes, 20, 0, false, fee, ts).explicitGet()
      sponsor = SponsorFeeTransaction.selfSigned(acc, IssuedAsset(issue.assetId), Some(1L), fee, ts).explicitGet()
      setScript = {
        val Right((testScript, _)) = ScriptCompiler.compile(
          """
            |{-# STDLIB_VERSION 3 #-}
            |{-# CONTENT_TYPE DAPP #-}
            |{-# SCRIPT_TYPE ACCOUNT #-}
            |
            |@Callable(invocation)
            |func default() = {
            |  WriteSet([])
            |}
            |
            |@Verifier(t)
            |func verify() = {
            |  true
            |}
            |""".stripMargin,
          ScriptEstimatorV1
        )

        SetScriptTransaction.selfSigned(acc, Some(testScript), fee, ts).explicitGet()
      }
      invoke = InvokeScriptTransaction.selfSigned(acc, acc, None, Nil, 10, sponsor.asset, ts).explicitGet()
      invoke1 = InvokeScriptTransaction.selfSigned(acc, acc, None, Nil, 20, sponsor.asset, ts).explicitGet()
      invoke2 = InvokeScriptTransaction.selfSigned(acc, acc, None, Nil, 20, sponsor.asset, ts + 1).explicitGet()
      invoke3 = InvokeScriptTransaction.selfSigned(acc, acc, None, Nil, 20, sponsor.asset, ts + 2).explicitGet()
    } yield (genesis, issue, sponsor, setScript, invoke, invoke1, invoke2, invoke3)

    forAll(preconditions) {
      case (genesis, issue, sponsor, setScript, invoke, invoke1, invoke2, invoke3) =>
        val fs = TestFunctionalitySettings.Stub.copy(resetInvalidInvokeSponsoredFeeHeight = 5, preActivatedFeatures = Map(
          BlockchainFeatures.SmartAccounts.id -> 0,
          BlockchainFeatures.SmartAssets.id   -> 0,
          BlockchainFeatures.FairPoS.id       -> 0,
          BlockchainFeatures.Ride4DApps.id    -> 0,
          BlockchainFeatures.FeeSponsorship.id    -> 0
        ))

        withDomain(WavesSettings.default().copy(blockchainSettings = WavesSettings.default().blockchainSettings.copy(functionalitySettings = fs))) { d =>
          val blockchain = d.blockchainUpdater

          def applyBlock(txs: Transaction*): Unit =
            blockchain.processBlock(TestBlock.create(System.currentTimeMillis(), blockchain.lastBlockId.getOrElse(TestBlock.randomSignature()), txs)) shouldBe 'right

          def sponsoredAssetBalance: Long = blockchain.balance(sponsor.sender, sponsor.asset)

          applyBlock(genesis) // h=1
          applyBlock(issue, sponsor) // h=2

          sponsoredAssetBalance shouldBe 20

          applyBlock(setScript, invoke) // h=3
          sponsoredAssetBalance shouldBe 30

          applyBlock(invoke1) // h=4
          sponsoredAssetBalance shouldBe 50
          val prePatchBlockId = blockchain.lastBlockId.get

          applyBlock(invoke2) // h=5 (patch)
          applyBlock(invoke3) // h=6 (after patch)
          applyBlock() // h=7 (reset ng state)
          sponsoredAssetBalance shouldBe 20

          // Test rollback
          blockchain.removeAfter(prePatchBlockId) shouldBe 'right
          sponsoredAssetBalance shouldBe 50
          applyBlock(invoke2)
          applyBlock(invoke3)
          applyBlock()
          sponsoredAssetBalance shouldBe 20
        }
    }
  }
}