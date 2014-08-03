package com.ibm.spark.kernel.protocol.v5.handler

import akka.actor.{Actor, ActorSelection, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.content._
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpecLike, Matchers}
import play.api.libs.json.Json

import scala.concurrent.duration._

object ExecuteRequestHandlerSpec {
  val config = """
    akka {
      loglevel = "WARNING"
    }"""
}

class ExecuteRequestHandlerSpec extends TestKit(
  ActorSystem("ExecuteRequestHandlerSpec",
    ConfigFactory.parseString(ExecuteRequestHandlerSpec.config))
) with ImplicitSender with FunSpecLike with Matchers with MockitoSugar {

  val actorLoader : ActorLoader = mock[ActorLoader]
  val probe : TestProbe = TestProbe()
  val mockSelection: ActorSelection = system.actorSelection(probe.ref.path.toString)
  when(actorLoader.loadRelayActor()).thenReturn(mockSelection)

  // Mocked interpreter that responds to an ExecuteRequest with an
  // ExecuteReply ExecuteResult tuple
  class MockInterpreterActor extends Actor {
    override def receive: Receive = {
      case message: ExecuteRequest =>
        val executeReply = new ExecuteReply("ok", 1, None, None, None, None, None)
        val executeResult = new ExecuteResult(2, Data(), Metadata())
        sender ! (executeReply, executeResult)
    }
  }

  // Add our handler and mock interpreter to the actor system
  val handlerActor = system.actorOf(Props(classOf[ExecuteRequestHandler], actorLoader))
  val fakeInterpreterActor = system.actorOf(Props(classOf[MockInterpreterActor], this))
  when(actorLoader.loadInterpreterActor()).thenReturn(system.actorSelection(fakeInterpreterActor.path.toString))

  // Construct a execute request kernel message for the handler
  val header = Header("","","","","")
  val kernelMessage = new KernelMessage(
    Seq[String](), "test message", header, header, Map[String, String](),
      """{
        "code": "spark code",
        "silent": false,
        "store_history": true,
        "user_expressions": {},
        "allow_stdin": false
      }"""
  )

  describe("Execute Request Handler") {
    it("should return execute reply and result") {
      // Send message to the handler
      handlerActor ! kernelMessage

      //  KernelStatus = busy
      val kernelBusyReply = probe.receiveOne(10.seconds).asInstanceOf[KernelMessage]
      val busyStatus = Json.parse(kernelBusyReply.contentString).as[KernelStatus]
      busyStatus.execution_state should be ("busy")

      //  ExecuteInput
      val kernelExecuteInputReply = probe.receiveOne(10.seconds).asInstanceOf[KernelMessage]
      val input = Json.parse(kernelExecuteInputReply.contentString).as[ExecuteInput]
      input.code should be ("spark code")

      // Interpreter should respond with tuple
      val kernelExecuteReply = probe.receiveOne(10.seconds).asInstanceOf[KernelMessage]
      val reply = Json.parse(kernelExecuteReply.contentString).as[ExecuteReply]
      reply.status should be ("ok")

      val kernelExecuteResult = probe.receiveOne(10.seconds).asInstanceOf[KernelMessage]
      val result = Json.parse(kernelExecuteResult.contentString).as[ExecuteResult]
      result.execution_count should be (2)

      //  KernelStatus = idle
      val kernelIdleReply = probe.receiveOne(10.seconds).asInstanceOf[KernelMessage]
      val idleStatus = Json.parse(kernelIdleReply.contentString).as[KernelStatus]
      idleStatus.execution_state should be ("idle")
    }
  }
}
